package kr.ac.pusan.pickle.provisioning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTaskFailedException;
import kr.ac.pusan.pickle.proxmox.ProxmoxTimeoutException;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.publishing.PublishingTeardownService;
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
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
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
 * <p>Protection model: every managed VM keeps the PVE {@code protection} flag
 * ON (armed at provisioning CONFIG); this pipeline is the only code that clears
 * it, immediately before the destroy. Two protection-related park modes exist:
 * the destroy-time re-check of the pickle {@code deletion_protection} setting
 * (a still-ON flag means a race like an ADMIN-notice-window re-enable, or a
 * path that skipped the API gate — parked without touching the PVE flag), and
 * the PVE "protected" destroy refusal (the flag was re-set out-of-band between
 * clear and delete — parked, never retried). A crash after the clear but
 * before the delete leaves the guest unprotected only until the sweeper
 * resumes the run — bounded minutes, on a VM already destined for destruction.</p>
 *
 * <p>Idempotency/serialization comes from the {@code provisioning_tasks} DELETE
 * task: the partial unique index allows one live task per VM, and the CAS
 * claim (PENDING/RETRYING → RUNNING) makes concurrent or duplicated enqueues
 * (sweeper every 5 min + direct force-delete enqueue) no-ops. Retries are
 * self-scheduled with backoff; after {@value #MAX_ATTEMPTS} attempts the task
 * parks as NEEDS_ADMIN while the VM stays DELETING. A parked task is a dead
 * end for automation: {@link #claimTask} never claims NEEDS_ADMIN and the
 * live-task unique index blocks a fresh task, so recovery is currently DB
 * surgery (flip the task to RETRYING or FAILED by hand); an admin re-run API
 * is planned for M4+.</p>
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
    private final NotificationService notificationService;
    private final PublishingTeardownService publishingTeardown;
    private final VmSettingsService vmSettingsService;

    public DeleteVmJob(VmRepository vmRepository, VmEventRepository vmEventRepository,
            ProvisioningTaskRepository taskRepository, NodeRepository nodeRepository,
            ProxmoxClient proxmoxClient, IpamService ipamService, JobScheduler jobScheduler,
            UserRepository userRepository, NotificationService notificationService,
            PublishingTeardownService publishingTeardown, VmSettingsService vmSettingsService) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.ipamService = ipamService;
        this.jobScheduler = jobScheduler;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.publishingTeardown = publishingTeardown;
        this.vmSettingsService = vmSettingsService;
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
            closeRacedLiveTask(vmId);
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
        }
        ProvisioningTask task = claimTask(vmId);
        if (task == null) {
            return; // another worker runs it, or the task is parked NEEDS_ADMIN
        }
        // Re-read after the task claim (TOCTOU guard): an admin cancel may have
        // raced the eligibility check above — destroying now would break the
        // cancel's promise, so the task is closed and the run stops.
        vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null || vm.getStatus() != VmStatus.DELETING || vm.getDeleteKind() == null) {
            taskRepository.fail(task.getId(),
                    "파기 직전 재검증 실패 — 삭제가 취소되었거나 상태가 변경되어 중단합니다", Instant.now());
            log.info("Delete job aborted for vm {}: cancellation raced the destroy", vmId);
            return;
        }
        // Destroy-time logical gate: deletion_protection ON at this point means
        // the acceptance-time check was raced (e.g. an owner re-enabled it
        // during the ADMIN notice window — a deliberate objection surface) or a
        // path skipped the API gate. Park for an operator BEFORE any teardown
        // (publishing must survive) and leave the PVE flag armed. Resolution:
        // override force-delete (persists the setting off AND resumes this
        // parked task) — a plain cancel is impossible here, the schedule has
        // already passed.
        if (vmSettingsService.bool(vmId, VmSettingsService.DELETION_PROTECTION)) {
            Instant now = Instant.now();
            String detail = "삭제 보호가 켜진 상태로 파기 시점에 도달했습니다 — 소유자 이의 제기"
                    + " 여부를 확인한 뒤, 회수하려면 오버라이드 강제 삭제를 실행하세요";
            log.error("Delete pipeline parked for vm {}: deletion_protection is on at destroy time",
                    vmId);
            taskRepository.park(task.getId(), detail, now);
            vmRepository.updateStatusDetail(vmId, VmStatus.DELETING, detail, now);
            return;
        }
        try {
            // Tear down HTTP publishing BEFORE the guest/IP goes away: a vhost
            // surviving past the 24h IP quarantine would route the deleted VM's
            // FQDN to whoever gets the address next (internal.md Link 2). A
            // failed teardown throws → backoff retry → NEEDS_ADMIN park.
            publishingTeardown.teardownForVmDeletion(vmId);
            destroyOnProxmox(vm);
            if (vm.getIpAllocationId() != null
                    && ipamService.release(vm.getIpAllocationId(), vmId)) {
                vmRepository.clearIpAllocation(vmId, vm.getIpAllocationId(), Instant.now());
            }
            Instant now = Instant.now();
            int updated = vmRepository.markDeleted(vmId, vm.getDeleteRequestedBy(), now);
            taskRepository.complete(task.getId(), now);
            if (updated == 1) {
                vmEventRepository.save(new VmEvent(vmId, VmEventType.DELETE, null, "VM 파기 완료"));
                notifyOrgAdmins(vm);
                log.info("Delete pipeline completed for vm {} (vmid {})", vmId, vm.getProxmoxVmid());
            }
        } catch (DestroyTargetMismatchException e) {
            // not retryable — park straight away for an operator to inspect
            Instant now = Instant.now();
            log.error("Delete pipeline parked for vm {}: {}", vmId, e.getMessage());
            taskRepository.park(task.getId(), e.getMessage(), now);
            vmRepository.updateStatusDetail(vmId, VmStatus.DELETING,
                    e.getMessage() + " — 관리자 확인이 필요합니다", now);
        } catch (ProtectedDestroyRefusalException e) {
            // PVE refused the destroy as protected even though the pipeline
            // cleared the flag just before — someone re-set it out-of-band
            // in that window. Never retry — park for an operator.
            Instant now = Instant.now();
            String detail = "Proxmox 보호(protection) 플래그로 파기가 거부되었습니다 — "
                    + "관리자가 오버라이드 강제 삭제로 회수해야 합니다"
                    + " (out-of-band 재설정 여부 확인)";
            log.error("Delete pipeline parked for vm {} (protected guest): {}", vmId,
                    e.getMessage());
            taskRepository.park(task.getId(), detail, now);
            vmRepository.updateStatusDetail(vmId, VmStatus.DELETING, detail, now);
        } catch (RuntimeException e) {
            handleFailure(task.getId(), vmId, e);
        }
    }

    /**
     * A PVE "VM is protected" refusal, anywhere in the cause chain. Only
     * consulted for the destroy call itself ({@link #destroyOnProxmox}) — a
     * failure of the preceding protection-clear PUT must never match, even if
     * PVE's error text happens to mention the protection option, so it stays
     * on the generic retry path.
     */
    private static boolean isProtectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("protect")) {
                return true;
            }
        }
        return false;
    }

    /** Destroy refused by PVE as protected — parked, never retried. */
    static final class ProtectedDestroyRefusalException extends RuntimeException {
        ProtectedDestroyRefusalException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * A canceled deletion can leave a live DELETE task behind when the cancel
     * raced an in-flight run (the same TOCTOU as above, seen from the other
     * side). Closing it here keeps re-enqueues from spinning on a task nobody
     * will ever complete. NEEDS_ADMIN tasks are left for the operator.
     */
    private void closeRacedLiveTask(long vmId) {
        Instant now = Instant.now();
        taskRepository.findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId,
                        ProvisioningTaskKind.DELETE,
                        Set.of(ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.RETRYING))
                .ifPresent(task -> taskRepository.transitionStatus(task.getId(), task.getStatus(),
                        ProvisioningTaskStatus.FAILED, "삭제가 취소되어 파기 태스크를 닫습니다", now));
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
     * cluster skips straight through. Before destroying, the guest's identity
     * is verified ({@link ManagedGuestIdentity}) — Proxmox recycles vmids, so
     * a mismatching guest means the number no longer belongs to this VM and
     * destroying it would kill a foreign machine.
     */
    private void destroyOnProxmox(Vm vm) {
        Integer vmid = vm.getProxmoxVmid();
        if (vmid == null) {
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "노드 정보를 찾을 수 없습니다 (node " + vm.getNodeId() + ")"));
        ClusterResource resource = proxmoxClient.clusterResources(node.getApiHost(), "vm").stream()
                .filter(r -> vmid.equals(r.vmid()))
                .findFirst().orElse(null);
        if (resource == null) {
            log.info("VM {} (vmid {}) already absent from Proxmox — destroy skipped",
                    vm.getId(), vmid);
            return;
        }
        if (!ManagedGuestIdentity.matches(vm, resource)) {
            throw new DestroyTargetMismatchException(
                    "파기 대상 불일치: vmid " + vmid + "의 게스트 이름 '" + resource.name()
                            + "'이(가) 호스트명 '" + vm.getHostname()
                            + "'과 다르고 pickle 태그도 없습니다");
        }
        shutdownIfRunning(node, vmid);
        // Disarm the always-on PVE protection as late as possible — after the
        // identity check and shutdown, immediately before the destroy. The
        // clear is an idempotent config PUT, so a retry re-clears harmlessly;
        // any failure here (transport or PVE-side) retries via handleFailure —
        // only the destroy itself may raise the never-retried protection park.
        proxmoxClient.setProtection(node.getApiHost(), node.getName(), vmid, false);
        try {
            String upid = proxmoxClient.delete(node.getApiHost(), node.getName(), vmid);
            proxmoxClient.awaitTask(node.getApiHost(), node.getName(), upid);
        } catch (RuntimeException e) {
            if (isProtectionError(e)) {
                throw new ProtectedDestroyRefusalException(e);
            }
            throw e;
        }
    }

    /** The guest at the stored vmid does not look like ours — never retried. */
    static final class DestroyTargetMismatchException extends RuntimeException {
        DestroyTargetMismatchException(String message) {
            super(message);
        }
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
            // status-guarded: a concurrent transition away from DELETING wins
            vmRepository.updateStatusDetail(vmId, VmStatus.DELETING,
                    error + " — 관리자 확인이 필요합니다", now);
        } else {
            taskRepository.markRetrying(taskId, error, now);
            vmRepository.updateStatusDetail(vmId, VmStatus.DELETING,
                    error + " — 자동 재시도 예정", now);
            jobScheduler.schedule(now.plus(backoffAfterAttempt(attempts)), () -> deleteVm(vmId));
        }
    }

    /**
     * The backoff {@link #handleFailure} schedules after the given attempt.
     * The sweeper uses it to leave RETRYING tasks alone while their scheduled
     * backoff run is still pending.
     */
    static Duration backoffAfterAttempt(int attempts) {
        return RETRY_BACKOFFS.get(Math.clamp(attempts - 1, 0, RETRY_BACKOFFS.size() - 1));
    }

    /** Contract: the final destruction is notified to the org's admins. */
    private void notifyOrgAdmins(Vm vm) {
        List<Long> admins = userRepository.findByRoleAndOrgId(UserRole.ORG_ADMIN, vm.getOrgId())
                .stream()
                .filter(admin -> admin.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
        notificationService.publish(admins, NotificationEvent.VM_DELETE_COMPLETED,
                Map.of("vmId", vm.getId(), "vmName", vm.getName()),
                "vm_delete_completed:" + vm.getId());
    }
}
