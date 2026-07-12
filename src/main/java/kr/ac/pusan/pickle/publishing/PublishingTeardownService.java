package kr.ac.pusan.pickle.publishing;

import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tears down a VM's HTTP publishing when the VM itself is deleted (docs/api/
 * internal.md Link 2). Without this, a published VM's vhost would outlive it:
 * after the 24h IP quarantine the address is reassigned to ANOTHER student's VM
 * and the deleted VM's FQDN silently routes there.
 *
 * <p>Two-phase and idempotent, mirroring unpublish: first the DB rows flip to
 * REMOVED (routes with a bumped generation so the agent accepts the ABSENT
 * apply, domains REMOVED, per-domain certs REVOKED); then each FQDN's ABSENT
 * state is pushed synchronously. A re-run after a partial failure re-pushes
 * only what the agent has not confirmed ({@code appliedGeneration} check) —
 * an already-confirmed removal, or one superseded by a newer generation (409
 * STALE), is a no-op.</p>
 *
 * <p>A push failure throws, so the caller ({@link
 * kr.ac.pusan.pickle.provisioning.DeleteVmJob}) retries with backoff and
 * eventually parks NEEDS_ADMIN — the guest/IP is never released while a live
 * vhost may still point at it.</p>
 */
@Service
public class PublishingTeardownService {

    private static final Logger log = LoggerFactory.getLogger(PublishingTeardownService.class);

    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final RouteGenerations routeGenerations;
    private final RouteApplyJob routeApplyJob;
    private final TransactionTemplate transactionTemplate;

    public PublishingTeardownService(DomainRepository domainRepository,
            RouteRepository routeRepository, CertificateRepository certificateRepository,
            RouteGenerations routeGenerations, RouteApplyJob routeApplyJob,
            TransactionTemplate transactionTemplate) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.routeGenerations = routeGenerations;
        this.routeApplyJob = routeApplyJob;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Full teardown for the deletion pipeline: mark rows REMOVED, then push
     * ABSENT for every FQDN and require confirmation.
     *
     * <p>Also enqueued as a JobRunr job (default retry policy) by the immediate
     * ERROR-VM delete, whose IP release cannot wait on the agent.</p>
     *
     * @throws IllegalStateException when any ABSENT apply fails — the deletion
     *         must not proceed to IP release past a live vhost
     */
    @Job(name = "publishing-teardown vm %0")
    public void teardownForVmDeletion(long vmId) {
        List<Long> routeIds = transactionTemplate.execute(tx -> markPublicationsRemoved(vmId));
        List<String> failures = new ArrayList<>();
        for (long routeId : routeIds) {
            ApplyOutcome.Kind kind = routeApplyJob.applyNow(routeId);
            // FAILED (agent rejected) and TRANSPORT (agent unreachable, removal
            // unconfirmed) both block the deletion from releasing the IP.
            if (kind == ApplyOutcome.Kind.FAILED || kind == ApplyOutcome.Kind.TRANSPORT) {
                failures.add(String.valueOf(routeId));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "퍼블리싱 vhost 제거 실패 (route " + String.join(", ", failures)
                            + ") — 라우트가 제거되기 전에는 VM/IP를 파기할 수 없습니다");
        }
    }

    /**
     * Marks every publication row of the VM removed and returns the ids of the
     * routes whose ABSENT state the agent has not confirmed yet. Idempotent:
     * already-REMOVED rows keep their (bumped) generation, and routes whose
     * removal the agent already applied are skipped.
     */
    @Transactional
    public List<Long> markPublicationsRemoved(long vmId) {
        List<Long> pending = new ArrayList<>();
        for (Domain domain : domainRepository.findByVmId(vmId)) {
            if (domain.getStatus() != DomainStatus.REMOVED) {
                domain.setStatus(DomainStatus.REMOVED);
            }
            certificateRepository.findByDomainId(domain.getId()).stream()
                    .filter(cert -> cert.getStatus() != CertificateStatus.REVOKED)
                    .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
            Route route = routeRepository.findFirstByDomainId(domain.getId()).orElse(null);
            if (route == null) {
                continue;
            }
            if (route.getStatus() != RouteStatus.REMOVED) {
                route.setStatus(RouteStatus.REMOVED);
                route.setGeneration(routeGenerations.next());
            }
            boolean confirmed = route.getAppliedGeneration() != null
                    && route.getAppliedGeneration() >= route.getGeneration();
            if (!confirmed) {
                pending.add(route.getId());
            }
        }
        if (!pending.isEmpty()) {
            log.info("vm {} deletion: tearing down {} published route(s)", vmId, pending.size());
        }
        return pending;
    }
}
