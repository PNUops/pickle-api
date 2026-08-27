package kr.ac.pusan.pickle.vm;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.admin.dto.ForceDeleteVmRequest;
import kr.ac.pusan.pickle.admin.dto.ScheduleVmDeletionRequest;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.provisioning.DeleteVmJob;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.publishing.PublishingTeardownService;
import kr.ac.pusan.pickle.relay.PortMappingTeardownService;
import kr.ac.pusan.pickle.sshkey.VmSshKeyRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
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
 * The three deletion flows (contract v0.3.1):
 *
 * <ul>
 *   <li><b>Self-delete</b> (workspace OWNER, or ORG_ADMIN of the org / SYS_ADMIN):
 *       immediate DELETING + async graceful shutdown, hard delete after
 *       {@code settings.vm_delete_grace_hours}; users cannot cancel.
 *       ERROR VMs (compensated create failures) collapse to an immediate
 *       DELETED with the IP released — there is nothing to destroy.</li>
 *   <li><b>Admin scheduled delete</b>: intent only (power state untouched),
 *       {@code scheduledFor} any future instant (the minimum-notice floor was
 *       dropped 2026-07-27 — it forced within-notice deletions into the
 *       immediate force delete, erasing the cancellable middle state; the
 *       console warns below the recommended 7 days instead), reason mandatory
 *       and mailed to the workspace.</li>
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

    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final VmRepository vmRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final VmAccessService vmAccessService;
    private final AdminVmAccess adminVmAccess;
    private final UserRepository userRepository;
    private final VmEventRepository vmEventRepository;
    private final SettingsService settingsService;
    private final IpamService ipamService;
    private final JobScheduler jobScheduler;
    private final DeleteVmJob deleteVmJob;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final PublishingTeardownService publishingTeardown;
    private final PortMappingTeardownService portMappingTeardown;
    private final VmSshKeyRepository vmSshKeyRepository;
    private final VmSettingsService vmSettingsService;

    public VmDeletionService(VmRepository vmRepository, WorkspaceMemberRepository workspaceMemberRepository, VmAccessService vmAccessService,
            UserRepository userRepository, VmEventRepository vmEventRepository,
            SettingsService settingsService, IpamService ipamService, JobScheduler jobScheduler,
            DeleteVmJob deleteVmJob, AuditService auditService, AuditIds auditIds,
            NotificationService notificationService,
            ProvisioningTaskRepository provisioningTaskRepository,
            PublishingTeardownService publishingTeardown,
            PortMappingTeardownService portMappingTeardown,
            VmSshKeyRepository vmSshKeyRepository, VmSettingsService vmSettingsService,
            AdminVmAccess adminVmAccess) {
        this.vmRepository = vmRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.vmAccessService = vmAccessService;
        this.userRepository = userRepository;
        this.vmEventRepository = vmEventRepository;
        this.settingsService = settingsService;
        this.ipamService = ipamService;
        this.jobScheduler = jobScheduler;
        this.deleteVmJob = deleteVmJob;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.notificationService = notificationService;
        this.provisioningTaskRepository = provisioningTaskRepository;
        this.publishingTeardown = publishingTeardown;
        this.portMappingTeardown = portMappingTeardown;
        this.vmSshKeyRepository = vmSshKeyRepository;
        this.vmSettingsService = vmSettingsService;
        this.adminVmAccess = adminVmAccess;
    }

    // ── self-delete (DELETE /vms/{vmId}) ───────────────────────────────────

    @Transactional
    public VmDeletionResponse selfDelete(AuthenticatedUser actor, UUID publicVmId, String ip) {
        DeletableVm deletable = requireDeletableByActor(actor, publicVmId);
        Vm vm = deletable.vm();
        long vmId = vm.getId();
        requireNoPendingDeletion(vm);
        requireNotDeletionProtected(vmId);
        if (vm.getStatus() == VmStatus.ERROR) {
            return deleteErrorVmImmediately(actor, deletable, ip);
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
                deletable.actorKind(),
                "삭제 접수 — " + KST.format(scheduledFor) + " (KST) 파기 예정"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SELF_DELETE,
                "vm", vm.getPublicId(), Map.of("name", vm.getName(), "orgId", auditIds.org(vm.getOrgId()),
                        "workspaceId", auditIds.workspace(vm.getWorkspaceId()), "scheduledFor", scheduledFor.toString()), ip);

        // Best-effort graceful shutdown; its failure never touches the schedule.
        enqueueAfterCommit(() -> deleteVmJob.gracefulShutdown(vmId));
        notificationService.publish(recipients(vm, true), NotificationEvent.VM_DELETE_ACCEPTED,
                Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(), "scheduledFor", scheduledFor), null);
        return new VmDeletionResponse(VmDeleteKind.SELF, scheduledFor, now, actor.publicId(), null, true);
    }

    /** ERROR VM: nothing to destroy — release the IP and finish immediately. */
    private VmDeletionResponse deleteErrorVmImmediately(AuthenticatedUser actor,
            DeletableVm deletable, String ip) {
        Vm vm = deletable.vm();
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
        // Same tx as the release below: no orphan mapping may survive its
        // target's IP release (the freed address can be re-assigned after
        // quarantine, and a leftover DNAT would deliver public traffic to the
        // next tenant's VM).
        portMappingTeardown.deleteMappingsForVm(vmId);
        vmSshKeyRepository.deleteByVmId(vmId);
        if (vm.getIpAllocationId() != null
                && ipamService.release(vm.getIpAllocationId(), vm.getId())) {
            vmRepository.clearIpAllocation(vm.getId(), vm.getIpAllocationId(), now);
        }
        vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.SELF_DELETE, actor.id(),
                deletable.actorKind(), "삭제 접수 — 생성 실패(ERROR) 상태, 유예 없이 즉시 파기"));
        vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.DELETE, actor.id(),
                deletable.actorKind(), "VM 파기 완료 — ERROR 상태(파기할 게스트 없음), IP 회수"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SELF_DELETE,
                "vm", vm.getPublicId(), Map.of("name", vm.getName(), "orgId", auditIds.org(vm.getOrgId()),
                        "workspaceId", auditIds.workspace(vm.getWorkspaceId()), "immediate", true), ip);
        return new VmDeletionResponse(VmDeleteKind.SELF, now, now, actor.publicId(), null, false);
    }

    // ── admin scheduled delete ─────────────────────────────────────────────

    @Transactional
    public VmDeletionResponse scheduleDeletion(AuthenticatedUser actor, UUID publicVmId,
            ScheduleVmDeletionRequest request, String ip) {
        Vm vm = adminVmAccess.requireAdministeredVm(actor, publicVmId);
        long vmId = vm.getId();
        requireNoPendingDeletion(vm);
        // CREATING is deliberately accepted (unlike self-delete): the schedule
        // is intent-only, and the sweeper waits for a sweepable power state —
        // so a schedule placed mid-provision simply defers until the VM lands.
        requireStatusOutside(vm, Set.of(VmStatus.DELETING, VmStatus.DELETED, VmStatus.NEEDS_ADMIN,
                VmStatus.ERROR), "현재 상태에서는 삭제를 접수할 수 없습니다.");
        requireNotDeletionProtected(vmId);

        Instant now = Instant.now();
        if (!request.scheduledFor().isAfter(now)) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("scheduledFor",
                    "삭제 예정일은 미래 시각이어야 합니다.")));
        }
        String reason = request.reason().strip();
        if (vmRepository.scheduleAdminDeletion(vmId, request.scheduledFor(), actor.id(),
                reason, now) == 0) {
            throw alreadyPendingDeletion();
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.SCHEDULE_DELETE, actor.id(), VmActorKind.ADMIN,
                "관리자 삭제 접수 — " + KST.format(request.scheduledFor()) + " (KST), 사유: " + reason));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_SCHEDULE_DELETE,
                "vm", vm.getPublicId(), Map.of("name", vm.getName(), "orgId", auditIds.org(vm.getOrgId()),
                        "workspaceId", auditIds.workspace(vm.getWorkspaceId()),
                        "scheduledFor", request.scheduledFor().toString(), "reason", reason), ip);
        notificationService.publish(recipients(vm, false), NotificationEvent.VM_DELETE_SCHEDULED,
                Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(), "reason", reason,
                        "scheduledFor", request.scheduledFor()), null);
        return new VmDeletionResponse(VmDeleteKind.ADMIN, request.scheduledFor(), now, actor.publicId(),
                reason, true);
    }

    // ── admin cancel (the only cancellation path — users have none) ─────

    @Transactional
    public MessageResponse cancelScheduledDeletion(AuthenticatedUser actor, UUID publicVmId, String ip) {
        Vm vm = adminVmAccess.requireAdministeredVm(actor, publicVmId);
        long vmId = vm.getId();
        Instant now = Instant.now();
        if (vm.getDeleteKind() == VmDeleteKind.SELF) {
            // SELF: the VM entered DELETING at acceptance — cancel restores
            // STOPPED and is valid only inside the grace window.
            boolean cancelable = vm.getStatus() != VmStatus.DELETED
                    && vm.getDeleteScheduledFor() != null
                    && vm.getDeleteScheduledFor().isAfter(now);
            if (!cancelable || vmRepository.transitionStatus(vmId, VmStatus.DELETING,
                    VmStatus.STOPPED, null, now) == 0) {
                throw notCancelable();
            }
            vmRepository.clearDeletion(vmId, now);
        } else if (vm.getDeleteKind() == VmDeleteKind.ADMIN) {
            // ADMIN: intent-only until the sweeper fires — the schedule may sit
            // past due for minutes with the VM intact, so cancel is gated on
            // destruction-not-started (the CAS refuses once DELETING), not on
            // the wall clock.
            if (vmRepository.cancelAdminDeletion(vmId, now) == 0) {
                throw notCancelable();
            }
        } else {
            throw notCancelable(); // FORCE (immediate) or no pending deletion
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.CANCEL_SCHEDULED_DELETE, actor.id(), VmActorKind.ADMIN,
                vm.getDeleteKind() == VmDeleteKind.SELF
                        ? "본인 삭제 취소 — VM은 STOPPED 상태로 유지"
                        : "관리자 삭제 취소"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_CANCEL_SCHEDULED_DELETE, "vm", vm.getPublicId(),
                Map.of("name", vm.getName(), "orgId", auditIds.org(vm.getOrgId()),
                        "workspaceId", auditIds.workspace(vm.getWorkspaceId()), "canceledKind", vm.getDeleteKind().name()), ip);
        notificationService.publish(recipients(vm, false), NotificationEvent.VM_DELETE_CANCELED,
                Map.of("vmId", vm.getPublicId(), "vmName", vm.getName()), null);
        return new MessageResponse("삭제가 취소되었습니다.");
    }

    // ── force delete (SYS_ADMIN, immediate, not cancelable) ────────────────

    @Transactional
    public MessageResponse forceDelete(AuthenticatedUser actor, UUID publicVmId,
            ForceDeleteVmRequest request, String ip) {
        Vm vm = vmRepository.findByPublicId(publicVmId).orElseThrow(VmAccessService::vmNotFound);
        long vmId = vm.getId();
        if (!vm.getName().equals(request.confirmName())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_CONFIRM_NAME_MISMATCH,
                    "확인용 이름이 일치하지 않습니다",
                    "입력한 이름이 VM 이름과 일치하지 않습니다. VM 이름을 정확히 입력해 주세요.");
        }
        // Deletion protection: refused by default; the SYS_ADMIN escalation path
        // (overrideProtection) bypasses it by persisting the setting to false —
        // the destroy pipeline re-checks the flag and clears the hypervisor-side
        // protection itself, immediately before the delete.
        boolean protectedVm = vmSettingsService.bool(vmId, VmSettingsService.DELETION_PROTECTION);
        if (protectedVm && !request.overridesProtection()) {
            throw deletionProtected(
                    "삭제 보호가 켜져 있습니다. 소유 워크스페이스 OWNER가 해제하거나, 회수가 시급하면 "
                            + "overrideProtection: true를 명시해 강제 삭제해야 합니다.");
        }
        boolean overrodeProtection = protectedVm && request.overridesProtection();
        Instant now = Instant.now();
        if (vmRepository.beginForceDeletion(vmId, actor.id(), now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다", "이미 파기된 VM입니다.");
        }
        if (overrodeProtection) {
            // Persist the override in the same tx: the destroy pipeline's
            // logical gate (and its sweeper-recovered run after a crash) sees
            // the flag OFF and proceeds. Audited below via overrodeProtection.
            vmSettingsService.disableDeletionProtection(vmId, actor.id());
        }
        failLiveProvisionTask(vmId);
        resumeParkedDeleteTask(vmId);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.FORCE_DELETE, actor.id(), VmActorKind.ADMIN,
                overrodeProtection ? "강제 삭제 접수 — 삭제 보호 오버라이드, 즉시 강제 종료 후 파기"
                        : "강제 삭제 접수 — 즉시 강제 종료 후 파기"));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_FORCE_DELETE,
                "vm", vm.getPublicId(), Map.of("name", vm.getName(), "orgId", auditIds.org(vm.getOrgId()),
                        "workspaceId", auditIds.workspace(vm.getWorkspaceId()), "overrodeProtection", overrodeProtection), ip);
        enqueueAfterCommit(() -> deleteVmJob.deleteVm(vmId));
        notificationService.publish(recipients(vm, true), NotificationEvent.VM_DELETE_FORCE,
                Map.of("vmId", vm.getPublicId(), "vmName", vm.getName()), null);
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

    /**
     * A previous destroy run may have parked the DELETE task as NEEDS_ADMIN
     * (destroy-time protection gate, PVE protected refusal, retry exhaustion).
     * {@code DeleteVmJob.claimTask} deliberately never claims NEEDS_ADMIN, so
     * without this the enqueued run below would be a silent no-op and the VM
     * would stay wedged in DELETING — force-delete is the advertised recovery
     * path for those parks, so it resumes the task to PENDING here. The
     * attempt budget is cleared with the transition (repository resume
     * convention): a retry-exhaustion park carries {@code attempts=MAX}, and
     * without the reset the resumed run would re-park on its first failure
     * with zero backoff retries.
     */
    private void resumeParkedDeleteTask(long vmId) {
        provisioningTaskRepository
                .findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId, ProvisioningTaskKind.DELETE,
                        Set.of(ProvisioningTaskStatus.NEEDS_ADMIN))
                .ifPresent(task -> provisioningTaskRepository.transitionStatusClearingAttempts(
                        task.getId(), ProvisioningTaskStatus.NEEDS_ADMIN,
                        ProvisioningTaskStatus.PENDING, Instant.now()));
    }

    // ── shared guards ──────────────────────────────────────────────────────

    /**
     * A VM this actor may delete, together with <b>how they got the right</b>.
     * The path is the member-facing one, but two of its four ways in are an
     * administrator's override, and an override is an intervention however
     * ordinary the endpoint looks: recording it as a member's own deletion
     * would put the administrator's name in the workspace's history, which is
     * exactly what this history withholds everywhere else.
     */
    private record DeletableVm(Vm vm, VmActorKind actorKind) {
    }

    /**
     * Self-delete authorization: an owner of the VM's access list, an owner of
     * the workspace that owns it (deletion is one of the three standing rights),
     * ORG_ADMIN of the VM's org, or SYS_ADMIN. Non-members and cross-org admins
     * get 404 (masking); anyone else who can see the VM gets 403.
     */
    private DeletableVm requireDeletableByActor(AuthenticatedUser actor, UUID vmId) {
        Vm vm = vmRepository.findByPublicId(vmId).orElseThrow(VmAccessService::vmNotFound);
        // Standing as a member is checked FIRST, and the order is the whole
        // point: an administrator who also owns this VM, or owns the workspace
        // that holds it, is deleting their own resource like anyone else. Ask
        // the role before the access list and their name disappears from their
        // own colleagues' history — the same failure that made this column
        // record the surface rather than the role.
        VmAccess access = vmAccessService.of(vm, actor.id());
        if (access.manages()) {
            return new DeletableVm(vm, VmActorKind.MEMBER);
        }
        if (actor.role() == UserRole.SYS_ADMIN) {
            return new DeletableVm(vm, VmActorKind.ADMIN);
        }
        if (actor.role() == UserRole.ORG_ADMIN) {
            // The admin override belongs to the org this VM is in, not to the
            // account's highest role somewhere else.
            if (!actor.administers(vm.getOrgId())) {
                throw VmAccessService.vmNotFound();
            }
            return new DeletableVm(vm, VmActorKind.ADMIN);
        }
        access.requireVisible();
        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                "VM을 삭제할 권한이 없습니다",
                "이 VM의 소유자, 워크스페이스 소유자 또는 관리자만 VM을 삭제할 수 있습니다.");
    }

    private void requireNoPendingDeletion(Vm vm) {
        if (vm.getDeleteKind() != null) {
            throw alreadyPendingDeletion();
        }
    }

    /** Refuses any delete acceptance while {@code deletion_protection} is on. */
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

    private static ApiException notCancelable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                "취소할 수 있는 삭제가 없습니다. 이미 파기가 시작되었거나 완료된 상태일 수 있습니다.");
    }

    // ── notifications / enqueue ────────────────────────────────────────────

    /**
     * Everyone this VM concerns — its grantees and the owners of the workspace that
     * owns it — optionally plus the org's admins (ACTIVE only).
     * Notifications are INSERTed in the deletion transaction itself, so they
     * exist iff the deletion intent committed; email leaves asynchronously via
     * the dispatcher.
     */
    private List<Long> recipients(Vm vm, boolean includeOrgAdmins) {
        Set<Long> userIds = new LinkedHashSet<>(notificationService.vmAudienceIds(vm));
        if (includeOrgAdmins) {
            userIds.addAll(notificationService.orgAdminIds(vm.getOrgId()));
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
