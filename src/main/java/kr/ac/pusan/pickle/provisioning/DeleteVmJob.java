package kr.ac.pusan.pickle.provisioning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.mail.MailSender;
import kr.ac.pusan.pickle.mail.VmLifecycleMailComposer;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTaskFailedException;
import kr.ac.pusan.pickle.proxmox.ProxmoxTimeoutException;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * DELETE pipeline worker (docs/plan/03 deletion): shut down if running
 * (ACPI with a 120 s guest timeout, force-stop fallback — the real pve1
 * capture shows freshly booted guests ignoring ACPI), destroy with purge,
 * release the IP into quarantine, then CAS the vm row to DELETED.
 *
 * <p>Idempotency/serialization comes from the {@code provisioning_tasks} DELETE
 * task: the partial unique index allows one live task per VM, and the CAS
 * claim (PENDING/RETRYING → RUNNING) makes concurrent or duplicated enqueues
 * (sweeper every 5 min + direct emergency enqueue) no-ops. Retries are
 * self-scheduled with backoff; after {@value #MAX_ATTEMPTS} attempts the task
 * parks as NEEDS_ADMIN while the VM stays DELETING, so an operator fix lets
 * the sweeper resume a fresh task later.</p>
 */
@Component
public class DeleteVmJob {

    static final int SHUTDOWN_TIMEOUT_SECONDS = 120;
    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(DeleteVmJob.class);

    private static final List<Duration> RETRY_BACKOFFS =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(5));

    private static final Set<ProvisioningTaskStatus> LIVE_STATUSES = Set.of(
            ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.RUNNING,
            ProvisioningTaskStatus.RETRYING, ProvisioningTaskStatus.NEEDS_ADMIN);

    private static final Set<VmStatus> POWER_STATES =
            Set.of(VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.REBOOTING);

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;
    private final IpamService ipamService;
    private final JobScheduler jobScheduler;
    private final UserRepository userRepository;
    private final MailSender mailSender;
    private final VmLifecycleMailComposer mailComposer;

    public DeleteVmJob(VmRepository vmRepository, VmEventRepository vmEventRepository,
            ProvisioningTaskRepository taskRepository, NodeRepository nodeRepository,
            ProxmoxClient proxmoxClient, IpamService ipamService, JobScheduler jobScheduler,
            UserRepository userRepository, MailSender mailSender,
            VmLifecycleMailComposer mailComposer) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.ipamService = ipamService;
        this.jobScheduler = jobScheduler;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.mailComposer = mailComposer;
    }

    /**
     * Best-effort graceful shutdown at self-delete acceptance time. Failure
     * never touches the deletion schedule — the final {@link #deleteVm} run
     * shuts the VM down again (with the force fallback) before destroying.
     */
    @Job(name = "vm-delete graceful-shutdown %0", retries = 0)
    public void gracefulShutdown(long vmId) {
        Vm vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null || vm.getStatus() != VmStatus.DELETING || vm.getProxmoxVmid() == null) {
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElse(null);
        if (node == null) {
            return;
        }
        try {
            shutdownIfRunning(node, vm.getProxmoxVmid());
        } catch (RuntimeException e) {
            log.warn("Graceful shutdown for deleting vm {} failed (schedule kept): {}",
                    vmId, e.getMessage());
        }
    }

    /** Executes the destroy pipeline once the destroy time arrived. */
    @Job(name = "vm-delete %0", retries = 0)
    public void deleteVm(long vmId) {
        Vm vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null || vm.getDeleteKind() == null) {
            log.info("Delete job skipped for vm {}: no pending deletion", vmId);
            return;
        }
        if (vm.getStatus() != VmStatus.DELETING) {
            // ADMIN-scheduled deletes keep the power state until destroy time.
            if (!POWER_STATES.contains(vm.getStatus())
                    || vmRepository.transitionStatus(vmId, vm.getStatus(), VmStatus.DELETING,
                            null, Instant.now()) == 0) {
                log.info("Delete job skipped for vm {} (status {})", vmId, vm.getStatus());
                return;
            }
            vm = vmRepository.findById(vmId).orElseThrow();
        }
        ProvisioningTask task = claimTask(vmId);
        if (task == null) {
            return; // another worker runs it, or the task is parked NEEDS_ADMIN
        }
        try {
            destroyOnProxmox(vm);
            if (vm.getIpAllocationId() != null) {
                ipamService.release(vm.getIpAllocationId());
            }
            Instant now = Instant.now();
            int updated = vmRepository.markDeleted(vmId, vm.getDeleteRequestedBy(), now);
            taskRepository.complete(task.getId(), now);
            if (updated == 1) {
                vmEventRepository.save(new VmEvent(vmId, VmEventType.DELETE, null, "VM 파기 완료"));
                notifyOrgAdmins(vm);
                log.info("Delete pipeline completed for vm {} (vmid {})", vmId, vm.getProxmoxVmid());
            }
        } catch (RuntimeException e) {
            handleFailure(task.getId(), vmId, e);
        }
    }

    /**
     * Claims the single live DELETE task (creating it when absent). Returns
     * null when the claim is lost: a concurrent run holds RUNNING, or the task
     * parked NEEDS_ADMIN and only an operator may resume.
     */
    private ProvisioningTask claimTask(long vmId) {
        Instant now = Instant.now();
        ProvisioningTask task = taskRepository.findFirstByVmIdAndKindAndStatusInOrderByIdDesc(
                vmId, ProvisioningTaskKind.DELETE, LIVE_STATUSES).orElse(null);
        if (task == null) {
            try {
                task = taskRepository.saveAndFlush(
                        new ProvisioningTask(vmId, ProvisioningTaskKind.DELETE));
            } catch (DataIntegrityViolationException lostInsertRace) {
                return null;
            }
        }
        int claimed = switch (task.getStatus()) {
            case PENDING -> taskRepository.startAttempt(task.getId(), now);
            case RETRYING -> taskRepository.resumeAttempt(task.getId(), now);
            default -> 0;
        };
        return claimed == 1 ? taskRepository.findById(task.getId()).orElse(null) : null;
    }

    /**
     * Destroys the Proxmox guest if it still exists. Idempotent: a VM without
     * a vmid (mock-provisioned / pre-clone crash) or already absent from the
     * cluster skips straight through.
     */
    private void destroyOnProxmox(Vm vm) {
        Integer vmid = vm.getProxmoxVmid();
        if (vmid == null) {
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "노드 정보를 찾을 수 없습니다 (node " + vm.getNodeId() + ")"));
        boolean exists = proxmoxClient.clusterResources(node.getApiHost(), "vm").stream()
                .anyMatch(resource -> vmid.equals(resource.vmid()));
        if (!exists) {
            log.info("VM {} (vmid {}) already absent from Proxmox — destroy skipped",
                    vm.getId(), vmid);
            return;
        }
        shutdownIfRunning(node, vmid);
        String upid = proxmoxClient.delete(node.getApiHost(), node.getName(), vmid);
        proxmoxClient.awaitTask(node.getApiHost(), node.getName(), upid);
    }

    /**
     * Graceful ACPI shutdown with a force-stop fallback. Unlike the user
     * shutdown op (contract: no fallback), the deletion flow falls back —
     * shutting down here is a courtesy step before destruction, and the real
     * pve1 capture (fixture 61) shows freshly booted guests ignoring ACPI.
     */
    private void shutdownIfRunning(Node node, int vmid) {
        ClusterResource resource = proxmoxClient.clusterResources(node.getApiHost(), "vm").stream()
                .filter(r -> Integer.valueOf(vmid).equals(r.vmid()))
                .findFirst().orElse(null);
        if (resource == null || !"running".equals(resource.status())) {
            return;
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

    /** Backoff-retry within {@link #MAX_ATTEMPTS}, then park as NEEDS_ADMIN. */
    private void handleFailure(long taskId, long vmId, RuntimeException e) {
        Instant now = Instant.now();
        String error = "삭제 실패: " + e.getMessage();
        int attempts = taskRepository.findById(taskId)
                .map(ProvisioningTask::getAttempts).orElse(MAX_ATTEMPTS);
        log.warn("Delete pipeline attempt {} failed for vm {}: {}", attempts, vmId, e.getMessage());
        if (attempts >= MAX_ATTEMPTS) {
            taskRepository.park(taskId, error, now);
            vmRepository.updateStatusDetail(vmId, error + " — 관리자 확인이 필요합니다", now);
        } else {
            taskRepository.markRetrying(taskId, error, now);
            vmRepository.updateStatusDetail(vmId, error + " — 자동 재시도 예정", now);
            Duration backoff = RETRY_BACKOFFS.get(Math.min(attempts - 1, RETRY_BACKOFFS.size() - 1));
            jobScheduler.schedule(now.plus(backoff), () -> deleteVm(vmId));
        }
    }

    /** Contract: the final destruction is notified to the org's admins. */
    private void notifyOrgAdmins(Vm vm) {
        for (User admin : userRepository.findByRoleAndOrgId(UserRole.ORG_ADMIN, vm.getOrgId())) {
            if (admin.getStatus() == UserStatus.ACTIVE) {
                mailSender.send(mailComposer.deleteCompleted(admin.getEmail(), vm.getName()));
            }
        }
    }
}
