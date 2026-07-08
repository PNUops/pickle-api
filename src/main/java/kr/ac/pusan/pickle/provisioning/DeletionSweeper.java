package kr.ac.pusan.pickle.provisioning;

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
 * Recurring scan (every 5 min) for pending deletions whose destroy time
 * arrived (docs/plan/03 deletion): SELF grace elapsed (VM already DELETING),
 * ADMIN notice date reached (VM still in its power state — {@link DeleteVmJob}
 * transitions it at destroy time), or an EMERGENCY whose direct enqueue was
 * lost to a crash. Enqueues are idempotent: the single live DELETE task and
 * its CAS claim in {@link DeleteVmJob} absorb duplicates, and a NEEDS_ADMIN
 * task keeps re-enqueued runs no-ops until an operator intervenes.
 */
@Component
public class DeletionSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeletionSweeper.class);

    private static final Set<VmStatus> SWEEPABLE_STATUSES = Set.of(
            VmStatus.DELETING, VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.REBOOTING);

    private final VmRepository vmRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final JobScheduler jobScheduler;
    private final DeleteVmJob deleteVmJob;

    public DeletionSweeper(VmRepository vmRepository, ProvisioningTaskRepository taskRepository,
            JobScheduler jobScheduler, DeleteVmJob deleteVmJob) {
        this.vmRepository = vmRepository;
        this.taskRepository = taskRepository;
        this.jobScheduler = jobScheduler;
        this.deleteVmJob = deleteVmJob;
    }

    @Recurring(id = "deletion-sweeper", cron = "*/5 * * * *")
    @Job(name = "deletion-sweeper", retries = 0)
    public void sweep() {
        Instant now = Instant.now();
        List<Vm> due = vmRepository.findDueForDeletion(now, SWEEPABLE_STATUSES);
        if (due.isEmpty()) {
            return;
        }
        log.info("Deletion sweeper: {} VM(s) due for destruction", due.size());
        for (Vm vm : due) {
            long vmId = vm.getId();
            if (inRetryBackoff(vmId, now)) {
                continue; // the failed run scheduled its own retry — don't bypass it
            }
            jobScheduler.enqueue(() -> deleteVmJob.deleteVm(vmId));
        }
    }

    /**
     * True while a RETRYING DELETE task is still inside the backoff window its
     * failing run scheduled — sweeping it now would resume the task early and
     * defeat the backoff. Once the window passed, sweeping again is the crash
     * recovery for a lost scheduled run.
     */
    private boolean inRetryBackoff(long vmId, Instant now) {
        return taskRepository.findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId,
                        ProvisioningTaskKind.DELETE, Set.of(ProvisioningTaskStatus.RETRYING))
                .map(task -> now.isBefore(task.getUpdatedAt()
                        .plus(DeleteVmJob.backoffAfterAttempt(task.getAttempts()))))
                .orElse(false);
    }
}
