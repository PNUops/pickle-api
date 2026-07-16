package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One custom-domain DNS verification pass (docs/plan/06). Polls the ownership TXT
 * ({@code _pickle-verify.<fqdn>}) and the A record (must point at the proxy IP),
 * flips the domain PENDING→VERIFYING→ACTIVE, and — on success — ensures a
 * Let's Encrypt certificate row exists and reports the live route to (re)apply.
 * Idempotent and bounded (one pass; the caller schedules re-checks).
 *
 * <p>Separate bean so the recurring scan and the enqueued single check both go
 * through the transactional proxy (self-invocation would bypass it).</p>
 */
@Service
public class DomainVerifier {

    private static final Logger log = LoggerFactory.getLogger(DomainVerifier.class);

    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final RouteGenerations routeGenerations;
    private final DnsResolver dnsResolver;
    private final PublishingProperties properties;
    private final VmRepository vmRepository;
    private final NotificationService notificationService;

    public DomainVerifier(DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, RouteGenerations routeGenerations,
            DnsResolver dnsResolver, PublishingProperties properties, VmRepository vmRepository,
            NotificationService notificationService) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.routeGenerations = routeGenerations;
        this.dnsResolver = dnsResolver;
        this.properties = properties;
        this.vmRepository = vmRepository;
        this.notificationService = notificationService;
    }

    /**
     * Runs one verification pass for a custom domain.
     *
     * @return the id of the live route to (re)apply when the domain is verified
     *         (and its cert issued/re-triggered), else empty
     */
    @Transactional
    public Optional<Long> verifyOne(long domainId) {
        Domain domain = domainRepository.findById(domainId).orElse(null);
        if (domain == null || domain.getKind() != DomainKind.CUSTOM
                || domain.getStatus() == DomainStatus.REMOVED) {
            return Optional.empty();
        }
        String fqdn = domain.getFqdn();
        boolean txtOk = dnsResolver.txtRecords(PublicationAssembler.VERIFY_RECORD_PREFIX + fqdn)
                .contains(domain.getVerificationToken());
        boolean aOk = dnsResolver.aRecords(fqdn).contains(properties.proxyPublicIp());
        domain.setTxtVerified(txtOk);
        domain.setAVerified(aOk);
        domain.setLastCheckedAt(Instant.now());

        if (txtOk && aOk) {
            domain.setStatus(DomainStatus.ACTIVE);
            domain.setLastError(null);
            if (domain.getVerifiedAt() == null) {
                domain.setVerifiedAt(Instant.now());
            }
            boolean certNeedsIssue = ensureCertificate(domain);
            Route route = routeRepository
                    .findFirstByDomainIdAndStatusNot(domainId, RouteStatus.REMOVED)
                    .orElse(null);
            if (route == null
                    || (route.getStatus() == RouteStatus.APPLIED && !certNeedsIssue)) {
                return Optional.empty(); // fully settled — nothing to re-apply
            }
            // The agent rejects a generation it already applied (409), so a
            // verify-triggered re-apply (cert re-issue, FAILED route retry) must
            // outrank the applied one — bump before handing the route back.
            route.setGeneration(routeGenerations.next());
            route.setStatus(RouteStatus.PENDING);
            route.setLastError(null);
            return Optional.of(route.getId());
        }

        // Transient miss: never demote an already-ACTIVE domain on a DNS flap.
        if (domain.getStatus() != DomainStatus.ACTIVE) {
            // Bounded polling: past the deadline the domain parks FAILED (the
            // recurring scan only re-checks PENDING/VERIFYING). A manual verify
            // still runs and succeeds whenever the records finally match.
            boolean deadlinePassed = domain.getCreatedAt() != null && Instant.now()
                    .isAfter(domain.getCreatedAt().plus(properties.verificationTimeout()));
            domain.setStatus(deadlinePassed ? DomainStatus.FAILED : DomainStatus.VERIFYING);
            if (deadlinePassed) {
                String error = "검증 기한(" + properties.verificationTimeout().toHours()
                        + "시간)이 지났습니다. DNS 레코드를 설정한 뒤 검증을 다시 실행해 주세요.";
                domain.setLastError(error);
                notifyVerificationFailed(domain, error);
                log.info("domain {} verification deadline passed — parked FAILED", fqdn);
                return Optional.empty();
            }
        }
        domain.setLastError(missReason(txtOk, aOk));
        log.debug("domain {} not yet verified (txt={}, a={})", fqdn, txtOk, aOk);
        return Optional.empty();
    }

    /**
     * Create the LE cert row if missing; re-arm a FAILED one for re-issue.
     *
     * @return whether issuance is still needed (anything but a live ACTIVE cert)
     */
    private boolean ensureCertificate(Domain domain) {
        Optional<Certificate> existing = certificateRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), CertificateStatus.REVOKED);
        if (existing.isEmpty()) {
            certificateRepository.save(Certificate.letsEncrypt(domain.getId(), domain.getFqdn()));
            return true;
        }
        Certificate cert = existing.get();
        if (cert.getStatus() == CertificateStatus.FAILED) {
            cert.setStatus(CertificateStatus.RENEWING);
            cert.setLastError(null);
        }
        return cert.getStatus() != CertificateStatus.ACTIVE;
    }

    /**
     * Verification parked FAILED (deadline passed): group OWNER/EDITORs get a
     * HIGH notice, deduped per domain — the recurring scan stops re-checking a
     * FAILED domain, so this fires once per park.
     */
    private void notifyVerificationFailed(Domain domain, String error) {
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        if (vm == null) {
            return;
        }
        notificationService.publish(notificationService.groupRoleHolderIds(vm.getGroupId(), true),
                NotificationEvent.DOMAIN_CONNECT_FAILED,
                Map.of("fqdn", domain.getFqdn(), "vmId", vm.getId(), "reason", error),
                "domain_verify_failed:" + domain.getId());
    }

    private static String missReason(boolean txtOk, boolean aOk) {
        if (!txtOk && !aOk) {
            return "TXT 소유권 레코드와 A 레코드를 아직 찾을 수 없습니다.";
        }
        return txtOk ? "A 레코드가 아직 프록시 IP를 가리키지 않습니다."
                : "TXT 소유권 레코드를 아직 찾을 수 없습니다.";
    }
}
