package kr.ac.pusan.pickle.publishing;

import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives custom-domain DNS verification (docs/plan/06). Two entry points, both
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

    @Job(name = "domain-verify %0", retries = 0)
    public void verify(long domainId) {
        checkAndApply(domainId);
    }

    private void checkAndApply(long domainId) {
        verifier.verifyOne(domainId).ifPresent(routeId -> {
            log.info("domain {} verified — enqueuing route-apply {}", domainId, routeId);
            jobScheduler.enqueue(() -> routeApplyJob.apply(routeId));
        });
    }
}
