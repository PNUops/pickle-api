package kr.ac.pusan.pickle.publishing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Recurring desired-state reconciler for HTTP routes (docs/plan/06). The
 * enqueued {@link RouteApplyJob} is fire-and-forget from the student's point of
 * view: if proxy-agent is unreachable when a student unpublishes, the DB says
 * REMOVED while nginx keeps serving the vhost — the opposite of intent — and
 * before this job only a manual admin resync would ever fix it. Every cycle
 * re-pushes routes whose desired state the agent has not confirmed
 * ({@code appliedGeneration < generation}): REMOVED → ABSENT always, PENDING →
 * PRESENT only for an ACTIVE domain. See
 * {@link RouteRepository#findUnconfirmedRouteIds} for why FAILED (422) is not
 * retried.
 *
 * <p>Thrash-bounded: at most {@link #MAX_ROUTES_PER_CYCLE} pushes per cycle, a
 * settle grace so routes with an apply in flight are left alone (a duplicate
 * push would be a harmless idempotent no-op anyway — the agent's generation
 * guard 409s anything superseded), and the cycle stops at the first TRANSPORT
 * outcome (the agent is down; hammering the remaining routes adds nothing).</p>
 */
@Component
public class RouteReconcileJob {

    public static final String JOB_ID = "route-reconcile";

    /** Leave a just-written route to its own enqueued apply (+ its retries). */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(1);
    static final int MAX_ROUTES_PER_CYCLE = 25;

    private static final Logger log = LoggerFactory.getLogger(RouteReconcileJob.class);

    private final RouteRepository routeRepository;
    private final RouteApplyJob routeApplyJob;

    public RouteReconcileJob(RouteRepository routeRepository, RouteApplyJob routeApplyJob) {
        this.routeRepository = routeRepository;
        this.routeApplyJob = routeApplyJob;
    }

    /**
     * One reconcile cycle. Public and argument-free so JobRunr's
     * {@code RecurringJobPostProcessor} can register it; tests call it directly.
     * Not transactional — each route settles (commits) independently inside
     * {@link RouteApplyJob#applyNow}.
     */
    @Recurring(id = JOB_ID, interval = "PT2M")
    @Job(name = JOB_ID, retries = 0)
    public void run() {
        List<Long> routeIds = routeRepository.findUnconfirmedRouteIds(
                Instant.now().minus(SETTLE_GRACE), PageRequest.of(0, MAX_ROUTES_PER_CYCLE));
        if (routeIds.isEmpty()) {
            return;
        }
        log.info("route-reconcile: {} unconfirmed route(s) to re-push", routeIds.size());
        for (long routeId : routeIds) {
            ApplyOutcome.Kind kind = routeApplyJob.applyNow(routeId);
            if (kind == ApplyOutcome.Kind.TRANSPORT) {
                log.warn("route-reconcile: proxy-agent unreachable — stopping this cycle "
                        + "(route {} and later retry next cycle)", routeId);
                return;
            }
        }
    }
}
