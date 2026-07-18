package kr.ac.pusan.pickle.vm;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.admin.dto.ForceDeleteVmRequest;
import kr.ac.pusan.pickle.admin.dto.ScheduleVmDeletionRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.provisioning.DeleteVmJob;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.publishing.PublishingTeardownService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The three deletion flows (contract v0.3.1, docs/plan/03):
 *
 * <ul>
 *   <li><b>Self-delete</b> (group OWNER, or ORG_ADMIN of the org / SYS_ADMIN):
 *       immediate DELETING + async graceful shutdown, hard delete after
 *       {@code settings.vm_delete_grace_hours}; users cannot cancel.
 *       ERROR VMs (compensated create failures) collapse to an immediate
 *       DELETED with the IP released — there is nothing to destroy.</li>
 *   <li><b>Admin scheduled delete</b>: intent only (power state untouched),
 *       {@code scheduledFor} at least {@code settings.vm_admin_delete_min_notice_days}
 *       out, reason mandatory and mailed to the group.</li>
 *   <li><b>Force delete</b> (SYS_ADMIN): name-confirmed immediate
 *       stop+destroy, never cancelable, audited separately.</li>
 * </ul>
 *
 * <p>Cancellation is admin-only and kind-aware: SELF returns the (already
 * shut down) VM to STOPPED, ADMIN merely clears the schedule, FORCE can
 * never be canceled. ORG_ADMIN scoping masks other orgs' VMs as 404.</p>
 */
@Service
public class VmDeletionService {

    static final int DEFAULT_GRACE_HOURS = 168;
    static final int DEFAULT_MIN_NOTICE_DAYS = 7;

    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final VmEventRepository vmEventRepository;
    private final SettingsService settingsService;
    private final IpamService ipamService;
    private final JobScheduler jobScheduler;
    private final DeleteVmJob deleteVmJob;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final PublishingTeardownService publishingTeardown;
    private final VmSettingsService vmSettingsService;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;

    public VmDeletionService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            UserRepository userRepository, VmEventRepository vmEventRepository,
            SettingsService settingsService, IpamService ipamService, JobScheduler jobScheduler,
            DeleteVmJob deleteVmJob, AuditService auditService,
            NotificationService notificationService,
            ProvisioningTaskRepository provisioningTaskRepository,
            PublishingTeardownService publishingTeardown, VmSettingsService vmSettingsService,
            NodeRepository nodeRepository, ProxmoxClient proxmoxClient) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.vmEventRepository = vmEventRepository;
        this.settingsService = settingsService;
        this.ipamService = ipamService;
        this.jobScheduler = jobScheduler;
        this.deleteVmJob = deleteVmJob;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.provisioningTaskRepository = provisioningTaskRepository;
        this.publishingTeardown = publishingTeardown;
        this.vmSettingsService = vmSettingsService;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
    }

    // ── self-delete (DELETE /vms/{vmId}) ───────────────────────────────────

    @Transactional
    public VmDeletionResponse selfDelete(AuthenticatedUser actor, long vmId, String ip) {
        Vm vm = requireDeletableByActor(actor, vmId);
        requireNoPendingDeletion(vm);
        requireNotDeletionProtected(vmId);
        if (vm.getStatus() == VmStatus.ERROR) {
            return deleteErrorVmImmediately(actor, vm, ip);
        }
        requireStatusOutside(vm, Set.of(VmStatus.CREATING, VmStatus.DELETING, VmStatus.DELETED,
                VmStatus.NEEDS_ADMIN), "현재 상태에서는 삭제할 수 없습니다.");

        Instant now = Instant.now();
        int graceHours = settingsService.integer(SettingsService.VM_DELETE_GRACE_HOURS,
                DEFAULT_GRACE_HOURS);
        Instant scheduledFor = now.plus(Duration.ofHours(graceHours));
        if (vmRepository.beginSelfDeletion(vmId, vm.getStatus(), scheduledFor, actor.id(), now) == 0) {
            throw alreadyPendingDeletion(); // lost a race with a concurrent transition
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.SELF_DELETE, actor.id(),
                "삭제 접수 — " + KST.format(scheduledFor) + " (KST) 파기 예정"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SELF_DELETE,
                "vm", vmId, Map.of("name", vm.getName(), "orgId", vm.getOrgId(),
                        "groupId", vm.getGroupId(), "scheduledFor", scheduledFor.toString()), ip);

        // Best-effort graceful shutdown; its failure never touches the schedule.
        enqueueAfterCommit(() -> deleteVmJob.gracefulShutdown(vmId));
        notificationService.publish(recipients(vm, true), NotificationEvent.VM_DELETE_ACCEPTED,
                Map.of("vmId", vmId, "vmName", vm.getName(), "scheduledFor", scheduledFor), null);
        return new VmDeletionResponse(VmDeleteKind.SELF, scheduledFor, now, actor.id(), null, true);
    }

    /** ERROR VM: nothing to destroy — release the IP and finish immediately. */
    private VmDeletionResponse deleteErrorVmImmediately(AuthenticatedUser actor, Vm vm, String ip) {
        Instant now = Instant.now();
        if (vmRepository.completeErrorDeletion(vm.getId(), actor.id(), now) == 0) {
            throw alreadyPendingDeletion();
        }
        // An ERROR VM was never publishable, but sweep defensively: rows flip
        // to REMOVED in this tx, the ABSENT pushes run as a retried job (the
        // IP is released immediately here, so removal must not be lost).
        long vmId = vm.getId();
        if (!publishingTeardown.markPublicationsRemoved(vmId).isEmpty()) {
            enqueueAfterCommit(() -> publishingTeardown.teardownForVmDeletion(vmId));
        }
        if (vm.getIpAllocationId() != null
                && ipamService.release(vm.getIpAllocationId(), vm.getId())) {
            vmRepository.clearIpAllocation(vm.getId(), vm.getIpAllocationId(), now);
        }
        vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.SELF_DELETE, actor.id(),
                "삭제 접수 — 생성 실패(ERROR) 상태, 유예 없이 즉시 파기"));
        vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.DELETE, actor.id(),
                "VM 파기 완료 — ERROR 상태(파기할 게스트 없음), IP 회수"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SELF_DELETE,
                "vm", vm.getId(), Map.of("name", vm.getName(), "orgId", vm.getOrgId(),
                        "groupId", vm.getGroupId(), "immediate", true), ip);
        return new VmDeletionResponse(VmDeleteKind.SELF, now, now, actor.id(), null, false);
    }

    // ── admin scheduled delete ─────────────────────────────────────────────

    @Transactional
    public VmDeletionResponse scheduleDeletion(AuthenticatedUser actor, long vmId,
            ScheduleVmDeletionRequest request, String ip) {
        Vm vm = requireOrgScopedVm(actor, vmId);
        requireNoPendingDeletion(vm);
        requireStatusOutside(vm, Set.of(VmStatus.DELETING, VmStatus.DELETED, VmStatus.NEEDS_ADMIN,
                VmStatus.ERROR), "현재 상태에서는 삭제를 접수할 수 없습니다.");
        requireNotDeletionProtected(vmId);

        Instant now = Instant.now();
        int noticeDays = settingsService.integer(SettingsService.ADMIN_DELETE_MIN_NOTICE_DAYS,
                DEFAULT_MIN_NOTICE_DAYS);
        if (request.scheduledFor().isBefore(now.plus(Duration.ofDays(noticeDays)))) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("scheduledFor",
                    "삭제 예정일은 최소 통보 기간(" + noticeDays + "일) 이후여야 합니다.")));
        }
        String reason = request.reason().strip();
        if (vmRepository.scheduleAdminDeletion(vmId, request.scheduledFor(), actor.id(),
                reason, now) == 0) {
            throw alreadyPendingDeletion();
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.SCHEDULE_DELETE, actor.id(),
                "관리자 삭제 접수 — " + KST.format(request.scheduledFor()) + " (KST), 사유: " + reason));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SCHEDULE_DELETE,
                "vm", vmId, Map.of("name", vm.getName(), "orgId", vm.getOrgId(),
                        "groupId", vm.getGroupId(),
                        "scheduledFor", request.scheduledFor().toString(), "reason", reason), ip);
        notificationService.publish(recipients(vm, false), NotificationEvent.VM_DELETE_SCHEDULED,
                Map.of("vmId", vmId, "vmName", vm.getName(), "reason", reason,
                        "scheduledFor", request.scheduledFor()), null);
        return new VmDeletionResponse(VmDeleteKind.ADMIN, request.scheduledFor(), now, actor.id(),
                reason, true);
    }

    // ── admin cancel (the only cancellation path — users have none) ─────

    @Transactional
    public MessageResponse cancelScheduledDeletion(AuthenticatedUser actor, long vmId, String ip) {
        Vm vm = requireOrgScopedVm(actor, vmId);
        Instant now = Instant.now();
        boolean cancelable = vm.getDeleteKind() != null
                && vm.getDeleteKind() != VmDeleteKind.FORCE
                && vm.getStatus() != VmStatus.DELETED
                && vm.getDeleteScheduledFor() != null
                && vm.getDeleteScheduledFor().isAfter(now);
        if (!cancelable) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "취소할 수 있는 삭제가 없습니다. 유예 기간이 지났다면 이미 파기된 것입니다.");
        }
        if (vm.getDeleteKind() == VmDeleteKind.SELF
                && vmRepository.transitionStatus(vmId, VmStatus.DELETING, VmStatus.STOPPED,
                        null, now) == 0) {
            // Lost a race with the sweeper/pipeline — treat as already destroyed.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "취소할 수 있는 삭제가 없습니다. 유예 기간이 지났다면 이미 파기된 것입니다.");
        }
        vmRepository.clearDeletion(vmId, now);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.CANCEL_SCHEDULED_DELETE, actor.id(),
                vm.getDeleteKind() == VmDeleteKind.SELF
                        ? "본인 삭제 취소 — VM은 STOPPED 상태로 유지"
                        : "관리자 삭제 취소"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_CANCEL_SCHEDULED_DELETE, "vm", vmId,
                Map.of("name", vm.getName(), "orgId", vm.getOrgId(),
                        "groupId", vm.getGroupId(), "canceledKind", vm.getDeleteKind().name()), ip);
        notificationService.publish(recipients(vm, false), NotificationEvent.VM_DELETE_CANCELED,
                Map.of("vmId", vmId, "vmName", vm.getName()), null);
        return new MessageResponse("삭제가 취소되었습니다.");
    }

    // ── force delete (SYS_ADMIN, immediate, not cancelable) ────────────────

    @Transactional
    public MessageResponse forceDelete(AuthenticatedUser actor, long vmId,
            ForceDeleteVmRequest request, String ip) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmDeletionService::vmNotFound);
        if (!vm.getName().equals(request.confirmName())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_CONFIRM_NAME_MISMATCH,
                    "확인용 이름이 일치하지 않습니다",
                    "입력한 이름이 VM 이름과 일치하지 않습니다. VM 이름을 정확히 입력해 주세요.");
        }
        // Deletion protection: refused by default; the SYS_ADMIN escalation path
        // (overrideProtection) bypasses it and clears the PVE flag before destroy.
        boolean protectedVm = vmSettingsService.bool(vmId, VmSettingsService.DELETION_PROTECTION);
        if (protectedVm && !request.overridesProtection()) {
            throw deletionProtected(
                    "삭제 보호가 켜져 있습니다. 소유 그룹 OWNER가 해제하거나, 회수가 시급하면 "
                            + "overrideProtection: true를 명시해 강제 삭제해야 합니다.");
        }
        boolean overrodeProtection = protectedVm && request.overridesProtection();
        Instant now = Instant.now();
        if (vmRepository.beginForceDeletion(vmId, actor.id(), now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다", "이미 파기된 VM입니다.");
        }
        if (overrodeProtection) {
            // Clear the hypervisor flag now so the async destroy is not itself
            // refused by PVE; a failure here fails the whole force-delete.
            clearPveProtection(vm);
        }
        failLiveProvisionTask(vmId);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.FORCE_DELETE, actor.id(),
                overrodeProtection ? "강제 삭제 접수 — 삭제 보호 오버라이드, 즉시 강제 종료 후 파기"
                        : "강제 삭제 접수 — 즉시 강제 종료 후 파기"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_FORCE_DELETE,
                "vm", vmId, Map.of("name", vm.getName(), "orgId", vm.getOrgId(),
                        "groupId", vm.getGroupId(), "overrodeProtection", overrodeProtection), ip);
        enqueueAfterCommit(() -> deleteVmJob.deleteVm(vmId));
        notificationService.publish(recipients(vm, true), NotificationEvent.VM_DELETE_FORCE,
                Map.of("vmId", vmId, "vmName", vm.getName()), null);
        return new MessageResponse("강제 삭제를 접수했습니다. VM이 즉시 강제 종료되고 파기됩니다.");
    }

    /**
     * Parks any live PROVISION task as FAILED before a force delete, so
     * a scheduled backoff retry cannot resume the pipeline and resurrect the
     * guest. A RUNNING worker mid-step may win a CAS race here — the pipeline
     * itself re-checks the VM status before every step and halts, so this is
     * belt-and-braces, not the only guard.
     */
    private void failLiveProvisionTask(long vmId) {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            ProvisioningTask task = provisioningTaskRepository
                    .findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId,
                            ProvisioningTaskKind.PROVISION, ProvisioningTaskStatus.live())
                    .orElse(null);
            if (task == null) {
                return;
            }
            if (provisioningTaskRepository.transitionStatus(task.getId(), task.getStatus(),
                    ProvisioningTaskStatus.FAILED, "강제 삭제로 프로비저닝 중단", now) == 1) {
                return;
            }
        }
    }

    // ── shared guards ──────────────────────────────────────────────────────

    /**
     * Self-delete authorization: group OWNER, ORG_ADMIN of the VM's org, or
     * SYS_ADMIN. Non-members and cross-org admins get 404 (masking); a member
     * below OWNER gets 403.
     */
    private Vm requireDeletableByActor(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmDeletionService::vmNotFound);
        if (actor.role() == UserRole.SYS_ADMIN) {
            return vm;
        }
        if (actor.role() == UserRole.ORG_ADMIN) {
            if (!vm.getOrgId().equals(actor.orgId())) {
                throw vmNotFound();
            }
            return vm;
        }
        GroupMemberRole role = groupMemberRepository
                .findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(VmDeletionService::vmNotFound);
        if (role != GroupMemberRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "VM을 삭제할 권한이 없습니다", "그룹 소유자(OWNER) 또는 관리자만 VM을 삭제할 수 있습니다.");
        }
        return vm;
    }

    /** Admin-op scope: ORG_ADMIN sees only their own org's VMs (404 otherwise). */
    private Vm requireOrgScopedVm(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmDeletionService::vmNotFound);
        if (actor.role() == UserRole.ORG_ADMIN && !vm.getOrgId().equals(actor.orgId())) {
            throw vmNotFound();
        }
        return vm;
    }

    private void requireNoPendingDeletion(Vm vm) {
        if (vm.getDeleteKind() != null) {
            throw alreadyPendingDeletion();
        }
    }

    /** Refuses any delete acceptance while {@code deletion_protection} is on (M6). */
    private void requireNotDeletionProtected(long vmId) {
        if (vmSettingsService.bool(vmId, VmSettingsService.DELETION_PROTECTION)) {
            throw deletionProtected("삭제 보호가 켜져 있어 삭제할 수 없습니다. 소유자가 VM 설정에서 "
                    + "삭제 보호를 해제한 뒤 다시 시도해 주세요.");
        }
    }

    private static ApiException deletionProtected(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_DELETION_PROTECTED,
                "삭제 보호가 설정된 VM입니다", detail);
    }

    /** Clears the PVE native protection flag (override path); no-op without a vmid. */
    private void clearPveProtection(Vm vm) {
        if (vm.getProxmoxVmid() == null) {
            return;
        }
        Node node = nodeRepository.findById(vm.getNodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "노드 정보를 찾을 수 없습니다 (node " + vm.getNodeId() + ")"));
        proxmoxClient.setProtection(node.getApiHost(), node.getName(), vm.getProxmoxVmid(), false);
    }

    private void requireStatusOutside(Vm vm, Set<VmStatus> forbidden, String baseDetail) {
        if (forbidden.contains(vm.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    baseDetail + " (현재 상태 " + vm.getStatus() + ")");
        }
    }

    private static ApiException alreadyPendingDeletion() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다", "이미 삭제가 접수되었거나 진행 중인 VM입니다.");
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }

    // ── notifications / enqueue ────────────────────────────────────────────

    /**
     * Group members' user ids, optionally plus the org's admins (ACTIVE only).
     * Notifications are INSERTed in the deletion transaction itself, so they
     * exist iff the deletion intent committed; email leaves asynchronously via
     * the dispatcher.
     */
    private List<Long> recipients(Vm vm, boolean includeOrgAdmins) {
        Set<Long> userIds = new LinkedHashSet<>();
        List<Long> memberIds = groupMemberRepository.findByGroupIdOrderByIdAsc(vm.getGroupId())
                .stream().map(GroupMember::getUserId).toList();
        userRepository.findAllById(memberIds).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .forEach(userIds::add);
        if (includeOrgAdmins) {
            userRepository.findByRoleAndOrgId(UserRole.ORG_ADMIN, vm.getOrgId()).stream()
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .map(User::getId)
                    .forEach(userIds::add);
        }
        return List.copyOf(userIds);
    }

    /** Same after-commit trade-off as ApprovalService/VmLifecycleService. */
    private void enqueueAfterCommit(JobLambda job) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(job);
            }
        });
    }
}
