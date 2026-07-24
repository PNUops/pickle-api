package kr.ac.pusan.pickle.provisioning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recurring 10-minute crash recovery for the provisioning/deletion pipelines
 * ({@code @Job retries = 0} means JobRunr never re-runs a crashed worker on
 * its own — without this job a crash mid-pipeline strands the task forever):
 *
 * <ol>
 *   <li>Tasks stuck in RUNNING for over {@link #STALE_AFTER} (the worker
 *       died mid-step) are CASed back to RETRYING and re-enqueued.</li>
 *   <li>Tasks stuck in PENDING/RETRYING for over {@link #STALE_AFTER} (their
 *       enqueue or scheduled backoff run was lost) are re-enqueued as-is —
 *       the claim CAS in the job absorbs duplicates.</li>
 *   <li>CREATING VMs with no PROVISION task at all (approve committed but the
 *       after-commit enqueue was lost) get a fresh PROVISION enqueue; the
 *       partial unique index on (vm_id, kind, live) prevents duplicates.</li>
 * </ol>
 *
 * <p>NEEDS_ADMIN tasks are never touched — they wait for an operator. Every
 * step is CAS-guarded, so racing a live worker is a harmless no-op.</p>
 */
@Component
public class StaleTaskRecoveryJob {

    public static final String JOB_ID = "stale-task-recovery";

    /** Far beyond any single step (agent wait max 5 min, backoff max 5 min). */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    /**
     * Power actions are single-shot (retries = 0) and short; a claim older than
     * this means the worker died before releasing it, so free it lest the VM's
     * power controls stay bricked.
     */
    static final Duration POWER_CLAIM_STALE_AFTER = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(StaleTaskRecoveryJob.class);

    private final ProvisioningTaskRepository taskRepository;
    private final VmRepository vmRepository;
    private final JobScheduler jobScheduler;
    private final ProvisioningService provisioningService;
    private final DeleteVmJob deleteVmJob;

    public StaleTaskRecoveryJob(ProvisioningTaskRepository taskRepository,
            VmRepository vmRepository, JobScheduler jobScheduler,
            ProvisioningService provisioningService, DeleteVmJob deleteVmJob) {
        this.taskRepository = taskRepository;
        this.vmRepository = vmRepository;
        this.jobScheduler = jobScheduler;
        this.provisioningService = provisioningService;
        this.deleteVmJob = deleteVmJob;
    }

    /**
     * One recovery cycle. Public and argument-free so JobRunr's
     * {@code RecurringJobPostProcessor} can register it; tests call it directly.
     */
    @Recurring(id = JOB_ID, interval = "PT10M")
    @Job(name = JOB_ID, retries = 0)
    public void recover() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minus(STALE_AFTER);
            reclaimStaleTasks(cutoff, now);
            reenqueueOrphanedCreatingVms(cutoff);
            releaseStalePowerClaims(now);
        } catch (RuntimeException e) {
            log.warn("stale-task recovery cycle failed: {}", e.toString());
        }
    }

    private void reclaimStaleTasks(Instant cutoff, Instant now) {
        List<ProvisioningTask> stale = taskRepository.findByStatusInAndUpdatedAtBefore(
                Set.of(ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.RUNNING,
                        ProvisioningTaskStatus.RETRYING),
                cutoff);
        for (ProvisioningTask task : stale) {
            if (task.getStatus() == ProvisioningTaskStatus.RUNNING
                    && taskRepository.transitionStatus(task.getId(),
                            ProvisioningTaskStatus.RUNNING, ProvisioningTaskStatus.RETRYING,
                            "워커 중단으로 회수된 작업(스테일 " + STALE_AFTER.toMinutes() + "분 초과)",
                            now) == 0) {
                continue; // the owning worker moved it meanwhile — leave it be
            }
            enqueue(task.getVmId(), task.getKind());
            log.warn("stale {} task {} for vm {} (was {}) reclaimed and re-enqueued",
                    task.getKind(), task.getId(), task.getVmId(), task.getStatus());
        }
    }

    /** Approve committed but the after-commit enqueue was lost to a crash. */
    private void reenqueueOrphanedCreatingVms(Instant cutoff) {
        for (Vm vm : vmRepository.findByStatusAndUpdatedAtBefore(VmStatus.CREATING, cutoff)) {
            boolean hasProvisionTask = taskRepository.findByVmIdOrderByIdDesc(vm.getId()).stream()
                    .anyMatch(task -> task.getKind() == ProvisioningTaskKind.PROVISION);
            if (!hasProvisionTask) {
                enqueue(vm.getId(), ProvisioningTaskKind.PROVISION);
                log.warn("stuck-CREATING vm {} has no PROVISION task — re-enqueued", vm.getId());
            }
        }
    }

    /** A crashed power worker's claim (retries = 0, never re-run) is freed here. */
    private void releaseStalePowerClaims(Instant now) {
        int freed = vmRepository.clearStalePowerActionClaims(
                now.minus(POWER_CLAIM_STALE_AFTER), now);
        if (freed > 0) {
            log.warn("freed {} stale power-action claim(s) (older than {} min)",
                    freed, POWER_CLAIM_STALE_AFTER.toMinutes());
        }
    }

    private void enqueue(long vmId, ProvisioningTaskKind kind) {
        switch (kind) {
            case PROVISION -> jobScheduler.enqueue(() -> provisioningService.provisionVm(vmId));
            case DELETE -> jobScheduler.enqueue(() -> deleteVmJob.deleteVm(vmId));
            // REINSTALL is contract-reserved; no pipeline exists yet
            case REINSTALL -> log.warn("stale REINSTALL task for vm {} ignored (no pipeline)", vmId);
        }
    }
}
