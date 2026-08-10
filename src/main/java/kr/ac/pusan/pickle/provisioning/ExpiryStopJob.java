package kr.ac.pusan.pickle.provisioning;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTaskFailedException;
import kr.ac.pusan.pickle.proxmox.ProxmoxTimeoutException;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-VM worker of the expiry auto-stop: graceful ACPI shutdown with a
 * bounded wait, then a force-stop fallback — an expiry stop MUST succeed
 * against a hung guest (same courtesy-then-force semantics as the deletion
 * flow, unlike the user shutdown op which never falls back).
 *
 * <p>{@link VmExpiryJob} enqueues this only after claiming the single-writer
 * {@code pending_power_action} slot; the claim is released on every exit path.
 * On a confirmed stop, one transaction CASes status → STOPPED with
 * {@code expiry_stopped_at} + Korean {@code status_detail}, appends the
 * {@code EXPIRE_STOP} event (actor null) and publishes
 * {@code VM_EXPIRY_STOPPED} (HIGH) to the workspace's OWNER/EDITORs and the org's
 * ORG_ADMINs. A Proxmox failure only logs — the claim is freed and the next
 * hourly sweep retries.</p>
 */
@Component
public class ExpiryStopJob {

    public static final String DETAIL_EXPIRY_STOPPED = "사용 기간 만료로 자동 종료";

    static final int SHUTDOWN_TIMEOUT_SECONDS = 120;

    private static final Logger log = LoggerFactory.getLogger(ExpiryStopJob.class);

    private static final List<VmStatus> STOPPABLE = List.of(VmStatus.RUNNING, VmStatus.REBOOTING);

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ExpiryStopJob(VmRepository vmRepository, VmEventRepository vmEventRepository,
            NodeRepository nodeRepository, ProxmoxClient proxmoxClient,
            NotificationService notificationService, TransactionTemplate transactionTemplate,
            Clock clock) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Job(name = "vm-expiry-stop %0", retries = 0)
    public void stop(long vmId) {
        try {
            run(vmId);
        } finally {
            // Release the claim on every exit path (crash leftovers are freed
            // by StaleTaskRecoveryJob like any other power claim).
            vmRepository.clearPowerActionClaim(vmId, Instant.now());
        }
    }

    private void run(long vmId) {
        Vm vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null || vm.getExpiryStoppedAt() != null
                || !STOPPABLE.contains(vm.getStatus())) {
            return; // already stopped/converged elsewhere
        }
        // Re-validate eligibility after the claim: a period extension or a
        // deletion may have raced the sweep.
        if (vm.getEndDate() == null
                || !vm.getEndDate().isBefore(ClockConfig.todayKst(clock))
                || vm.getDeleteScheduledFor() != null || vm.getDeleteRequestedAt() != null) {
            log.info("expiry stop skipped for vm {}: no longer eligible", vmId);
            return;
        }
        if (vm.getProxmoxVmid() == null) {
            log.warn("expiry stop skipped for vm {}: no Proxmox VMID", vmId);
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElse(null);
        if (node == null) {
            log.warn("expiry stop skipped for vm {}: node {} not found", vmId, vm.getNodeId());
            return;
        }
        try {
            shutdownThenForceStop(node, vm.getProxmoxVmid());
        } catch (RuntimeException e) {
            // Claim is freed in the finally; the next hourly sweep retries.
            log.warn("expiry stop of vm {} (vmid {}) failed: {}", vmId, vm.getProxmoxVmid(),
                    e.getMessage());
            return;
        }
        finalizeStop(vm);
    }

    /** Graceful ACPI first; a hung guest gets the force-stop fallback. */
    private void shutdownThenForceStop(Node node, int vmid) {
        ClusterResource resource = proxmoxClient.clusterResources(node.getApiHost(), "vm").stream()
                .filter(r -> Integer.valueOf(vmid).equals(r.vmid()))
                .findFirst().orElse(null);
        if (resource == null || !"running".equals(resource.status())) {
            return; // guest already down — just converge the DB state
        }
        try {
            String upid = proxmoxClient.shutdown(node.getApiHost(), node.getName(), vmid,
                    SHUTDOWN_TIMEOUT_SECONDS);
            proxmoxClient.awaitTask(node.getApiHost(), node.getName(), upid,
                    Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS + 60L));
        } catch (ProxmoxTaskFailedException | ProxmoxTimeoutException acpiUnresponsive) {
            log.info("ACPI shutdown of vmid {} failed ({}); falling back to force stop",
                    vmid, acpiUnresponsive.getMessage());
            String upid = proxmoxClient.stop(node.getApiHost(), node.getName(), vmid);
            proxmoxClient.awaitTask(node.getApiHost(), node.getName(), upid);
        }
    }

    /** One tx: status CAS + expiry marker + EXPIRE_STOP event + notification. */
    private void finalizeStop(Vm vm) {
        long vmId = vm.getId();
        transactionTemplate.executeWithoutResult(tx -> {
            if (vmRepository.finishExpiryStop(vmId, STOPPABLE, VmStatus.STOPPED,
                    DETAIL_EXPIRY_STOPPED, Instant.now()) == 0) {
                log.info("expiry stop of vm {} lost the CAS — already transitioned", vmId);
                return;
            }
            vmEventRepository.save(new VmEvent(vmId, VmEventType.EXPIRE_STOP, null,
                    DETAIL_EXPIRY_STOPPED));
            Set<Long> recipients = new LinkedHashSet<>();
            recipients.addAll(notificationService.vmResponsibleIds(vm));
            recipients.addAll(notificationService.orgAdminIds(vm.getOrgId()));
            notificationService.publish(recipients, NotificationEvent.VM_EXPIRY_STOPPED,
                    Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(), "endDate",
                            String.valueOf(vm.getEndDate())),
                    "vm-expiry-stopped:%d:%s".formatted(vmId, vm.getEndDate()));
            log.info("vm {} auto-stopped: usage period ended {}", vmId, vm.getEndDate());
        });
    }
}
