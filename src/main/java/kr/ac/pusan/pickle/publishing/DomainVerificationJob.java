package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives custom-domain DNS verification. Two entry points, both
 * delegating to {@link DomainVerifier#verifyOne(long)} and enqueuing a
 * {@link RouteApplyJob} apply when a domain verifies (its vhost + LE cert can go
 * live):
 *
 * <ul>
 *   <li>{@link #verifyDueDomains()} — recurring scan of PENDING/VERIFYING custom
 *       domains;</li>
 *   <li>{@link #verify(long)} — enqueued single check (first publish of a custom
 *       domain, and the manual {@code POST /domains/{id}/verify} retry).</li>
 * </ul>
 */
@Component
public class DomainVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(DomainVerificationJob.class);
    static final String RECURRING_ID = "domain-verification";

    private final DomainVerifier verifier;
    private final DomainRepository domainRepository;
    private final RouteApplyJob routeApplyJob;
    private final JobScheduler jobScheduler;

    /** Domains with a verify enqueued/running — {@link #requestVerify} dedupe. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public DomainVerificationJob(DomainVerifier verifier, DomainRepository domainRepository,
            RouteApplyJob routeApplyJob, JobScheduler jobScheduler) {
        this.verifier = verifier;
        this.domainRepository = domainRepository;
        this.routeApplyJob = routeApplyJob;
        this.jobScheduler = jobScheduler;
    }

    @Recurring(id = RECURRING_ID, interval = "PT1M")
    @Job(name = "domain-verification scan")
    public void verifyDueDomains() {
        List<Domain> due = domainRepository.findByKindAndStatusIn(DomainKind.CUSTOM,
                List.of(DomainStatus.PENDING, DomainStatus.VERIFYING));
        for (Domain domain : due) {
            checkAndApply(domain.getId());
        }
    }

    /**
     * Enqueues a single verify unless one is already queued/running for this
     * domain. DNS lookups are slow even with bounded timeouts, and the manual
     * trigger ({@code POST /domains/{id}/verify}) must not be able to stack
     * duplicate jobs onto the shared JobRunr pool.
     */
    public void requestVerify(long domainId) {
        if (!inFlight.add(domainId)) {
            log.debug("domain-verify {} already in flight — enqueue skipped", domainId);
            return;
        }
        try {
            jobScheduler.enqueue(() -> verify(domainId));
        } catch (RuntimeException e) {
            inFlight.remove(domainId);
            throw e;
        }
    }

    @Job(name = "domain-verify %0", retries = 0)
    public void verify(long domainId) {
        try {
            checkAndApply(domainId);
        } finally {
            inFlight.remove(domainId);
        }
    }

    private void checkAndApply(long domainId) {
        verifier.verifyOne(domainId).ifPresent(routeId -> {
            log.info("domain {} verified — enqueuing route-apply {}", domainId, routeId);
            jobScheduler.enqueue(() -> routeApplyJob.apply(routeId));
        });
    }
}
