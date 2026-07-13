package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * All task state transitions are compare-and-set JPQL updates (same pattern
 * as {@code VmRepository.transitionStatus}): the {@code from} guard makes
 * JobRunr job re-runs idempotent — 0 rows updated means the task already
 * moved on and the caller must stop.
 */
public interface ProvisioningTaskRepository
        extends JpaRepository<ProvisioningTask, Long>, JpaSpecificationExecutor<ProvisioningTask> {

    List<ProvisioningTask> findByVmIdOrderByIdDesc(Long vmId);

    /** VM ids that currently have a task in the given statuses (poller/reconciler race guard). */
    @Query("select distinct t.vmId from ProvisioningTask t where t.status in :statuses")
    Set<Long> findVmIdsWithStatusIn(@Param("statuses") Collection<ProvisioningTaskStatus> statuses);

    Optional<ProvisioningTask> findFirstByVmIdAndKindAndStatusInOrderByIdDesc(
            Long vmId, ProvisioningTaskKind kind, Collection<ProvisioningTaskStatus> statuses);

    /** Stale-task recovery scan: tasks that stopped moving before the cutoff. */
    List<ProvisioningTask> findByStatusInAndUpdatedAtBefore(
            Collection<ProvisioningTaskStatus> statuses, Instant cutoff);

    /** CAS status transition, recording the error (null clears it). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.status = :to, t.lastError = :lastError, t.updatedAt = :now
             where t.id = :id and t.status = :from
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") ProvisioningTaskStatus from,
            @Param("to") ProvisioningTaskStatus to, @Param("lastError") String lastError,
            @Param("now") Instant now);

    /** CAS status transition that also counts the attempt. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.status = :to, t.attempts = t.attempts + 1, t.updatedAt = :now
             where t.id = :id and t.status = :from
            """)
    int transitionStatusCountingAttempt(@Param("id") Long id,
            @Param("from") ProvisioningTaskStatus from, @Param("to") ProvisioningTaskStatus to,
            @Param("now") Instant now);

    /**
     * Advances the step pointer; guarded by the current step and RUNNING so a
     * concurrent duplicate run cannot double-advance. Attempts are per-step
     * (contract {@code ProvisioningTaskView.attempts}, ≤3 per step), so the
     * counter resets to 1 — the run currently executing the next step.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.currentStep = :fromStep + 1, t.attempts = 1, t.updatedAt = :now
             where t.id = :id and t.currentStep = :fromStep and t.status = :status
            """)
    int advanceStep(@Param("id") Long id, @Param("fromStep") int fromStep,
            @Param("status") ProvisioningTaskStatus status, @Param("now") Instant now);

    /** Advances the step pointer of a RUNNING task exactly once. */
    default int advanceStep(Long id, int fromStep, Instant now) {
        return advanceStep(id, fromStep, ProvisioningTaskStatus.RUNNING, now);
    }

    /** Stores the JobRunr job id for console/debug cross-reference. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.jobrunrJobId = :jobrunrJobId, t.updatedAt = :now
             where t.id = :id
            """)
    int attachJobrunrJob(@Param("id") Long id, @Param("jobrunrJobId") String jobrunrJobId,
            @Param("now") Instant now);

    /** First run: PENDING → RUNNING, counting the attempt. */
    default int startAttempt(Long id, Instant now) {
        return transitionStatusCountingAttempt(id, ProvisioningTaskStatus.PENDING,
                ProvisioningTaskStatus.RUNNING, now);
    }

    /** Retry run: RETRYING → RUNNING, counting the attempt. */
    default int resumeAttempt(Long id, Instant now) {
        return transitionStatusCountingAttempt(id, ProvisioningTaskStatus.RETRYING,
                ProvisioningTaskStatus.RUNNING, now);
    }

    /**
     * Admin re-run of a parked task: NEEDS_ADMIN → RUNNING with the attempt
     * budget and error reset, so the resumed step gets fresh retries.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.status = :to, t.attempts = 1, t.lastError = null, t.updatedAt = :now
             where t.id = :id and t.status = :from
            """)
    int transitionStatusResettingAttempts(@Param("id") Long id,
            @Param("from") ProvisioningTaskStatus from, @Param("to") ProvisioningTaskStatus to,
            @Param("now") Instant now);

    /** NEEDS_ADMIN → RUNNING (admin re-run), resetting attempts and error. */
    default int reactivate(Long id, Instant now) {
        return transitionStatusResettingAttempts(id, ProvisioningTaskStatus.NEEDS_ADMIN,
                ProvisioningTaskStatus.RUNNING, now);
    }

    /**
     * Admin retry API (M5): parks the task back in the queue instead of
     * claiming it — NEEDS_ADMIN → RETRYING with the attempt budget and error
     * cleared, so the re-enqueued job claims RETRYING → RUNNING like any
     * backoff retry and counts the fresh attempt itself. (Claiming RUNNING
     * here would make the job's own claim CAS see an unclaimable task.)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProvisioningTask t
               set t.status = :to, t.attempts = 0, t.lastError = null, t.updatedAt = :now
             where t.id = :id and t.status = :from
            """)
    int transitionStatusClearingAttempts(@Param("id") Long id,
            @Param("from") ProvisioningTaskStatus from, @Param("to") ProvisioningTaskStatus to,
            @Param("now") Instant now);

    /** NEEDS_ADMIN → RETRYING (admin retry API); 0 rows = not retryable. */
    default int requeueForAdminRetry(Long id, Instant now) {
        return transitionStatusClearingAttempts(id, ProvisioningTaskStatus.NEEDS_ADMIN,
                ProvisioningTaskStatus.RETRYING, now);
    }

    /** RUNNING → RETRYING with the failure that caused the backoff. */
    default int markRetrying(Long id, String lastError, Instant now) {
        return transitionStatus(id, ProvisioningTaskStatus.RUNNING,
                ProvisioningTaskStatus.RETRYING, lastError, now);
    }

    /** Parks the task for an operator, keeping everything known in lastError. */
    default int park(Long id, String lastError, Instant now) {
        return transitionStatus(id, ProvisioningTaskStatus.RUNNING,
                ProvisioningTaskStatus.NEEDS_ADMIN, lastError, now);
    }

    /** RUNNING → DONE, clearing lastError. */
    default int complete(Long id, Instant now) {
        return transitionStatus(id, ProvisioningTaskStatus.RUNNING,
                ProvisioningTaskStatus.DONE, null, now);
    }

    /** RUNNING → FAILED (terminal; compensation already ran, docs/plan/03). */
    default int fail(Long id, String lastError, Instant now) {
        return transitionStatus(id, ProvisioningTaskStatus.RUNNING,
                ProvisioningTaskStatus.FAILED, lastError, now);
    }
}
