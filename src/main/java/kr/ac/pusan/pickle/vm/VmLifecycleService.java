package kr.ac.pusan.pickle.vm;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.tx.AfterCommit;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.provisioning.VmPowerJobs;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-facing VM power control (contract ops startVm/shutdownVm/rebootVm/
 * forceStopVm). Endpoints only validate and write intent; every Proxmox call
 * happens in {@link VmPowerJobs} enqueued after commit.
 *
 * <p>Authorization per contract: workspace <b>MEMBER+</b>. A non-member answers
 * 404 (existence of other workspaces' VMs stays private, same masking convention
 * as the admin org scope), a VIEWER answers 403 {@code WORKSPACE_ROLE_INSUFFICIENT}.</p>
 *
 * <p>State machine per contract: start only from {@code STOPPED}, shutdown/
 * reboot only from {@code RUNNING}, force-stop from {@code RUNNING}/
 * {@code REBOOTING}; everything else (incl. {@code NEEDS_ADMIN}) is a 409
 * {@code VM_INVALID_STATE}. Reboot records its intent as the {@code REBOOTING}
 * transition so force-stop can target a hung reboot.</p>
 *
 * <p>Duplicate actions are serialized by a claim: start/shutdown/
 * force-stop atomically claim {@code vms.pending_power_action} (CAS on
 * "no action in flight AND status is an allowed source") before enqueuing the
 * worker, so two rapid duplicates get exactly one 202 and one 409. The worker
 * ({@link VmPowerJobs}) releases the claim on every exit path, a crashed
 * worker's stale claim is freed by {@code StaleTaskRecoveryJob}, and the status
 * poller skips claimed VMs. Reboot serializes through its visible
 * {@code REBOOTING} transition instead of the claim column, so a force-stop can
 * still interrupt a hung reboot and the poller can converge a crashed one.</p>
 *
 * <p>Admin intervention (contract v0.17.0, {@code POST /admin/vms/{vmId}/…}):
 * the same intents under org-scoped authorization instead of workspace membership.
 * The admin path deliberately skips the workspace-role gates — including stop
 * protection, which is a workspace-internal guard (MEMBER vs EDITOR); the admin is
 * the emergency operator and every accepted intervention is audited. The
 * expiry guard on start stays: the sanctioned path is a period extension
 * first. State machine and claim protocol are identical to the user path.</p>
 */
@Service
public class VmLifecycleService {

    private final VmRepository vmRepository;
    private final VmAccessService vmAccessService;
    private final VmSettingsService vmSettingsService;
    private final AdminVmAccess adminVmAccess;
    private final AuditService auditService;
    private final JobScheduler jobScheduler;
    private final VmPowerJobs vmPowerJobs;
    private final Clock clock;

    public VmLifecycleService(VmRepository vmRepository, VmAccessService vmAccessService,
            VmSettingsService vmSettingsService, AdminVmAccess adminVmAccess,
            AuditService auditService, JobScheduler jobScheduler, VmPowerJobs vmPowerJobs,
            Clock clock) {
        this.vmRepository = vmRepository;
        this.vmAccessService = vmAccessService;
        this.vmSettingsService = vmSettingsService;
        this.adminVmAccess = adminVmAccess;
        this.auditService = auditService;
        this.jobScheduler = jobScheduler;
        this.vmPowerJobs = vmPowerJobs;
        this.clock = clock;
    }

    @Transactional
    public MessageResponse start(AuthenticatedUser actor, UUID vmId) {
        Vm vm = requireMemberControllableVm(actor, vmId).vm();
        long id = vm.getId();
        // Expiry guard: a past end date (KST, inclusive end) refuses start
        // even from STOPPED — only PATCH /admin/vms/{vmId}/period lifts it.
        if (isExpired(vm)) {
            throw expired(vm, "사용 기간(종료일 %s)이 만료되어 시작할 수 없습니다. 관리자에게 기간 연장을 요청해 주세요.");
        }
        claimPowerAction(id, PowerAction.START, List.of(VmStatus.STOPPED),
                "STOPPED 상태의 VM만 시작할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit("enqueue vm start #" + id,
                () -> vmPowerJobs.start(id, actorId, false));
        return new MessageResponse("VM 시작 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse shutdown(AuthenticatedUser actor, UUID vmId) {
        Controllable controllable = requireMemberControllableVm(actor, vmId);
        long id = controllable.vm().getId();
        requireStopAllowed(id, controllable.role());
        claimPowerAction(id, PowerAction.SHUTDOWN, List.of(VmStatus.RUNNING),
                "RUNNING 상태의 VM만 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit("enqueue vm shutdown #" + id,
                () -> vmPowerJobs.shutdown(id, actorId, false));
        return new MessageResponse("VM 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse reboot(AuthenticatedUser actor, UUID vmId) {
        Controllable controllable = requireMemberControllableVm(actor, vmId);
        long id = controllable.vm().getId();
        requireStopAllowed(id, controllable.role());
        // Intent is visible immediately (and force-stop can target a hung
        // reboot); the CAS loses to a concurrent transition OR a live claim → 409.
        if (vmRepository.claimReboot(id, VmStatus.RUNNING, VmStatus.REBOOTING, Instant.now()) == 0) {
            throw powerConflict(id, "RUNNING 상태의 VM만 재부팅할 수 있습니다.");
        }
        long actorId = actor.id();
        enqueueAfterCommit("enqueue vm reboot #" + id,
                () -> vmPowerJobs.reboot(id, actorId, false));
        return new MessageResponse("VM 재부팅 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse forceStop(AuthenticatedUser actor, UUID vmId) {
        Controllable controllable = requireMemberControllableVm(actor, vmId);
        long id = controllable.vm().getId();
        requireStopAllowed(id, controllable.role());
        claimPowerAction(id, PowerAction.FORCE_STOP,
                List.of(VmStatus.RUNNING, VmStatus.REBOOTING),
                "RUNNING 또는 REBOOTING 상태의 VM만 강제 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit("enqueue vm forceStop #" + id,
                () -> vmPowerJobs.forceStop(id, actorId, false));
        return new MessageResponse("VM 강제 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    /* ─── admin intervention (org-scoped, stop-protection bypass, audited) ─── */

    @Transactional
    public MessageResponse adminStart(AuthenticatedUser actor, UUID vmId, String ip) {
        return adminIntent(actor, vmId, VmPowerAction.START, ip);
    }

    @Transactional
    public MessageResponse adminShutdown(AuthenticatedUser actor, UUID vmId, String ip) {
        return adminIntent(actor, vmId, VmPowerAction.SHUTDOWN, ip);
    }

    @Transactional
    public MessageResponse adminReboot(AuthenticatedUser actor, UUID vmId, String ip) {
        return adminIntent(actor, vmId, VmPowerAction.REBOOT, ip);
    }

    @Transactional
    public MessageResponse adminForceStop(AuthenticatedUser actor, UUID vmId, String ip) {
        return adminIntent(actor, vmId, VmPowerAction.FORCE_STOP, ip);
    }

    private MessageResponse adminIntent(AuthenticatedUser actor, UUID vmId, VmPowerAction action,
            String ip) {
        Vm vm = adminVmAccess.requireWritableVm(actor, vmId);
        ApiException refusal = adminPower(actor, vm, action, null, ip);
        if (refusal != null) {
            throw refusal;
        }
        return new MessageResponse(switch (action) {
            case START -> "VM 시작 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.";
            case SHUTDOWN -> "VM 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.";
            case REBOOT -> "VM 재부팅 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.";
            case FORCE_STOP -> "VM 강제 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.";
        });
    }

    /**
     * Places one admin power intent on a VM the caller may already write, and
     * answers with the refusal rather than throwing it: the bulk change runs
     * this for many VMs inside one transaction, where an exception would mark
     * the whole batch for rollback. Null means the intent was claimed, its
     * audit row registered, and its worker queued for after commit.
     */
    public @Nullable ApiException adminPower(AuthenticatedUser actor, Vm vm, VmPowerAction action,
            @Nullable UUID batchId, String ip) {
        long id = vm.getId();
        long actorId = actor.id();
        switch (action) {
            case START -> {
                // Same expiry guard as the member path: extend the period first.
                if (isExpired(vm)) {
                    return expired(vm, "사용 기간(종료일 %s)이 만료되어 시작할 수 없습니다. 먼저 기간을 연장해 주세요.");
                }
                if (vmRepository.claimPowerAction(id, PowerAction.START, List.of(VmStatus.STOPPED),
                        Instant.now()) == 0) {
                    return powerConflict(id, "STOPPED 상태의 VM만 시작할 수 있습니다.");
                }
                recordAdminPowerAudit(actor, vm, AuditService.VM_ADMIN_START, batchId, ip);
                enqueueAfterCommit("enqueue vm start #" + id,
                () -> vmPowerJobs.start(id, actorId, true));
            }
            case SHUTDOWN -> {
                if (vmRepository.claimPowerAction(id, PowerAction.SHUTDOWN,
                        List.of(VmStatus.RUNNING), Instant.now()) == 0) {
                    return powerConflict(id, "RUNNING 상태의 VM만 종료할 수 있습니다.");
                }
                recordAdminPowerAudit(actor, vm, AuditService.VM_ADMIN_SHUTDOWN, batchId, ip);
                enqueueAfterCommit("enqueue vm shutdown #" + id,
                () -> vmPowerJobs.shutdown(id, actorId, true));
            }
            case REBOOT -> {
                if (vmRepository.claimReboot(id, VmStatus.RUNNING, VmStatus.REBOOTING,
                        Instant.now()) == 0) {
                    return powerConflict(id, "RUNNING 상태의 VM만 재부팅할 수 있습니다.");
                }
                recordAdminPowerAudit(actor, vm, AuditService.VM_ADMIN_REBOOT, batchId, ip);
                enqueueAfterCommit("enqueue vm reboot #" + id,
                () -> vmPowerJobs.reboot(id, actorId, true));
            }
            case FORCE_STOP -> {
                if (vmRepository.claimPowerAction(id, PowerAction.FORCE_STOP,
                        List.of(VmStatus.RUNNING, VmStatus.REBOOTING), Instant.now()) == 0) {
                    return powerConflict(id,
                            "RUNNING 또는 REBOOTING 상태의 VM만 강제 종료할 수 있습니다.");
                }
                recordAdminPowerAudit(actor, vm, AuditService.VM_ADMIN_FORCE_STOP, batchId, ip);
                enqueueAfterCommit("enqueue vm forceStop #" + id,
                () -> vmPowerJobs.forceStop(id, actorId, true));
            }
        }
        return null;
    }

    /** The states one power intent may start from, as the claim allows them. */
    public static List<VmStatus> allowedSources(VmPowerAction action) {
        return switch (action) {
            case START -> List.of(VmStatus.STOPPED);
            case SHUTDOWN, REBOOT -> List.of(VmStatus.RUNNING);
            case FORCE_STOP -> List.of(VmStatus.RUNNING, VmStatus.REBOOTING);
        };
    }

    /** A past end date (KST, inclusive end) refuses start until the period is extended. */
    public boolean isExpired(Vm vm) {
        return vm.getEndDate() != null && vm.getEndDate().isBefore(ClockConfig.todayKst(clock));
    }

    private static ApiException expired(Vm vm, String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_EXPIRED,
                "사용 기간이 만료된 VM입니다", detail.formatted(vm.getEndDate()));
    }

    /**
     * Every accepted admin power intervention leaves an audit row (the VM event
     * itself is written by the worker with the admin as actor, same as the
     * member path). Reads are not audited, matching the other admin surfaces.
     */
    private void recordAdminPowerAudit(AuthenticatedUser actor, Vm vm, String action,
            @Nullable UUID batchId, String ip) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", vm.getStatus().name());
        if (batchId != null) {
            detail.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), action, "vm", vm.getPublicId(),
                detail, ip);
    }

    /**
     * Atomically claims the power-action slot, or throws a 409: a claim fails
     * because either another action is already in flight (duplicate) or the
     * status is not an allowed source. {@code label} must match the
     * {@link VmPowerJobs} action name (informational only; the worker clears the
     * claim by id).
     */
    private void claimPowerAction(long vmId, String label, Collection<VmStatus> allowed,
            String invalidStateDetail) {
        if (vmRepository.claimPowerAction(vmId, label, allowed, Instant.now()) == 0) {
            throw powerConflict(vmId, invalidStateDetail);
        }
    }

    /** Names the {@link VmPowerJobs} worker method for the pending-action label. */
    private static final class PowerAction {
        static final String START = "START";
        static final String SHUTDOWN = "SHUTDOWN";
        static final String FORCE_STOP = "FORCE_STOP";

        private PowerAction() {
        }
    }

    /** A power-controllable VM plus the requester's role in its workspace. */
    private record Controllable(Vm vm, ResourceRole role) {
    }

    /**
     * Resolves the VM for a power op: unknown id and non-member both answer
     * 404 (masking), a member below MEMBER answers 403. The role is returned so
     * stop-protected ops can additionally require EDITOR.
     */
    private Controllable requireMemberControllableVm(AuthenticatedUser actor, UUID vmId) {
        VmAccess access = vmAccessService.of(actor, vmId);
        Vm vm = access.requireAtLeast(ResourceRole.MEMBER,
                "VM을 제어할 권한이 없습니다", "이 VM의 참여자 이상만 VM 전원을 제어할 수 있습니다.");
        return new Controllable(vm, access.role());
    }

    /**
     * Stop protection: when {@code stop_protection} is on, shutdown/reboot/
     * force-stop require workspace EDITOR+; a MEMBER is refused 409
     * {@code VM_STOP_PROTECTED}. Start is deliberately unaffected. Only the
     * member-facing power ops call this — the admin intervention path
     * (contract v0.17.0) deliberately bypasses it, per the class javadoc.
     */
    private void requireStopAllowed(long vmId, ResourceRole role) {
        if (role.atLeast(ResourceRole.EDITOR)) {
            return;
        }
        if (vmSettingsService.bool(vmId, VmSettingsService.STOP_PROTECTION)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_STOP_PROTECTED,
                    "중지 보호가 설정된 VM입니다",
                    "중지 보호가 켜져 있어 이 VM의 편집자 이상만 종료·재부팅·강제 종료할 수 있습니다.");
        }
    }

    /**
     * Builds the 409 for a failed claim/transition: an in-flight action reads
     * as "already processing", otherwise the per-op invalid-state message with
     * the (re-read) current status. Both use {@code VM_INVALID_STATE} for
     * consistency with the existing power 409s.
     */
    private ApiException powerConflict(long vmId, String invalidStateDetail) {
        Vm current = vmRepository.findById(vmId).orElseThrow(VmAccessService::vmNotFound);
        if (current.getPendingPowerAction() != null) {
            return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "이미 진행 중인 전원 작업이 있습니다. 잠시 후 다시 시도해 주세요.");
        }
        return invalidState(current, invalidStateDetail);
    }

    private static ApiException invalidState(Vm vm, String baseDetail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                baseDetail + " (현재 상태 " + vm.getStatus() + ")");
    }

    /**
     * JobRunr's storage provider commits with its own connection, so an
     * in-transaction enqueue could run before the intent row is visible or
     * survive a rollback — enqueue after commit instead (same trade-off as
     * ApprovalService: a crash in the tiny window loses the job; the status
     * poller/reconciler surfaces the drift).
     */
    private void enqueueAfterCommit(String label, JobLambda job) {
        AfterCommit.run(label, () -> jobScheduler.enqueue(job));
    }
}
