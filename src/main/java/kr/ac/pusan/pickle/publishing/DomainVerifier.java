package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.Optional;
import kr.ac.pusan.pickle.config.PublishingProperties;
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
    private final DnsResolver dnsResolver;
    private final PublishingProperties properties;

    public DomainVerifier(DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, DnsResolver dnsResolver,
            PublishingProperties properties) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.dnsResolver = dnsResolver;
        this.properties = properties;
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
            ensureCertificate(domain);
            return routeRepository.findFirstByDomainIdAndStatusNot(domainId, RouteStatus.REMOVED)
                    .map(Route::getId);
        }

        // Transient miss: never demote an already-ACTIVE domain on a DNS flap.
        if (domain.getStatus() != DomainStatus.ACTIVE) {
            domain.setStatus(DomainStatus.VERIFYING);
        }
        domain.setLastError(missReason(txtOk, aOk));
        log.debug("domain {} not yet verified (txt={}, a={})", fqdn, txtOk, aOk);
        return Optional.empty();
    }

    /** Create the LE cert row if missing; re-arm a FAILED one for re-issue. */
    private void ensureCertificate(Domain domain) {
        Optional<Certificate> existing = certificateRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), CertificateStatus.REVOKED);
        if (existing.isEmpty()) {
            certificateRepository.save(Certificate.letsEncrypt(domain.getId(), domain.getFqdn()));
            return;
        }
        Certificate cert = existing.get();
        if (cert.getStatus() == CertificateStatus.FAILED) {
            cert.setStatus(CertificateStatus.RENEWING);
            cert.setLastError(null);
        }
    }

    private static String missReason(boolean txtOk, boolean aOk) {
        if (!txtOk && !aOk) {
            return "TXT 소유권 레코드와 A 레코드를 아직 찾을 수 없습니다.";
        }
        return txtOk ? "A 레코드가 아직 프록시 IP를 가리키지 않습니다."
                : "TXT 소유권 레코드를 아직 찾을 수 없습니다.";
    }
}
