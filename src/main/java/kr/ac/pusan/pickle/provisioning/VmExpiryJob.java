package kr.ac.pusan.pickle.provisioning;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly VM usage-period sweep. Two phases,
 * both idempotent under hourly re-runs and crash recovery:
 *
 * <ol>
 *   <li><b>Notices</b>: for each dated live VM without a pending deletion, the
 *       applicable stage is the smallest configured D-day covering
 *       {@code daysLeft} (a VM created late skips straight to the current
 *       stage). The stage CAS ({@code last_expiry_notice_stage} descends only)
 *       plus the per-recipient dedup key ({@code vm-expiry:<id>:<endDate>:D<s>}
 *       — the end date in the key re-arms notices after an extension) make
 *       re-runs send nothing. Stage mark + notification insert share one tx.</li>
 *   <li><b>Auto-stop</b> (when {@code vm_expiry_autostop_enabled}): VMs whose
 *       end date (inclusive, KST) has passed are stopped via a per-VM
 *       {@link ExpiryStopJob} after claiming the single-writer power slot — a
 *       lost claim just skips; the next hour retries.</li>
 * </ol>
 */
@Component
public class VmExpiryJob {

    public static final String JOB_ID = "vm-expiry";

    private static final Logger log = LoggerFactory.getLogger(VmExpiryJob.class);

    /** Notice recipients still make sense for anything not being deleted. */
    private static final List<VmStatus> NOTICE_STATUSES =
            List.of(VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.REBOOTING);

    private static final List<VmStatus> STOP_STATUSES =
            List.of(VmStatus.RUNNING, VmStatus.REBOOTING);

    private final VmRepository vmRepository;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final ExpiryStopJob expiryStopJob;
    private final JobScheduler jobScheduler;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public VmExpiryJob(VmRepository vmRepository, SettingsService settingsService,
            NotificationService notificationService, ExpiryStopJob expiryStopJob,
            JobScheduler jobScheduler, TransactionTemplate transactionTemplate, Clock clock) {
        this.vmRepository = vmRepository;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.expiryStopJob = expiryStopJob;
        this.jobScheduler = jobScheduler;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "0 * * * *")
    @Job(name = JOB_ID, retries = 0)
    public void run() {
        LocalDate today = ClockConfig.todayKst(clock);
        try {
            sendNotices(today);
        } catch (RuntimeException e) {
            log.warn("expiry notice phase failed: {}", e.toString());
        }
        try {
            enqueueAutoStops(today);
        } catch (RuntimeException e) {
            log.warn("expiry auto-stop phase failed: {}", e.toString());
        }
    }

    private void sendNotices(LocalDate today) {
        List<Integer> stages = settingsService.intList(SettingsService.VM_EXPIRY_NOTICE_DAYS)
                .stream()
                .filter(stage -> stage >= 0)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (stages.isEmpty()) {
            return;
        }
        int maxStage = stages.getLast();
        for (Vm vm : vmRepository.findExpiryNoticeCandidates(NOTICE_STATUSES, today,
                today.plusDays(maxStage))) {
            long daysLeft = ChronoUnit.DAYS.between(today, vm.getEndDate());
            // smallest configured stage covering daysLeft — a late-created VM
            // skips outer stages and goes straight to the current one
            Integer stage = stages.stream()
                    .filter(s -> daysLeft <= s)
                    .findFirst()
                    .orElse(null);
            if (stage == null) {
                continue;
            }
            notifyStage(vm, stage);
        }
    }

    /** Stage CAS + notification insert in one tx per VM (both or neither). */
    private void notifyStage(Vm vm, int stage) {
        transactionTemplate.executeWithoutResult(tx -> {
            if (vmRepository.markExpiryNoticeStage(vm.getId(), stage, Instant.now()) == 0) {
                return; // this or an inner stage already went out
            }
            List<Long> recipients = notificationService.vmResponsibleIds(vm);
            notificationService.publish(recipients, NotificationEvent.VM_EXPIRY_NOTICE,
                    Map.of("vmId", vm.getId(), "vmName", vm.getName(),
                            "endDate", String.valueOf(vm.getEndDate()), "days", stage),
                    "vm-expiry:%d:%s:D%d".formatted(vm.getId(), vm.getEndDate(), stage));
            log.info("vm {} expiry notice D-{} sent to {} recipient(s) (end date {})",
                    vm.getId(), stage, recipients.size(), vm.getEndDate());
        });
    }

    private void enqueueAutoStops(LocalDate today) {
        if (!settingsService.bool(SettingsService.VM_EXPIRY_AUTOSTOP_ENABLED, true)) {
            return;
        }
        for (Vm vm : vmRepository.findExpiryStopCandidates(STOP_STATUSES, today)) {
            long vmId = vm.getId();
            // Single-writer power slot: a lost claim (user action or another
            // sweep in flight) just skips — the next hourly run retries.
            if (vmRepository.claimPowerAction(vmId, "EXPIRE_STOP", STOP_STATUSES,
                    Instant.now()) == 0) {
                log.info("expiry stop of vm {} skipped: power slot busy", vmId);
                continue;
            }
            jobScheduler.enqueue(() -> expiryStopJob.stop(vmId));
        }
    }
}
