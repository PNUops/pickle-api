package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.provisioning.VmPowerJobs;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * User-facing VM power control (contract ops startVm/shutdownVm/rebootVm/
 * forceStopVm). Endpoints only validate and write intent; every Proxmox call
 * happens in {@link VmPowerJobs} enqueued after commit (docs/plan/03).
 *
 * <p>Authorization per contract: group <b>MEMBER+</b>. A non-member answers
 * 404 (existence of other groups' VMs stays private, same masking convention
 * as the admin org scope), a VIEWER answers 403 {@code GROUP_ROLE_INSUFFICIENT}.</p>
 *
 * <p>State machine per contract: start only from {@code STOPPED}, shutdown/
 * reboot only from {@code RUNNING}, force-stop from {@code RUNNING}/
 * {@code REBOOTING}; everything else (incl. {@code NEEDS_ADMIN}) is a 409
 * {@code VM_INVALID_STATE}. Reboot records its intent as the {@code REBOOTING}
 * transition so force-stop can target a hung reboot.</p>
 *
 * <p>Duplicate actions are serialized by a claim (C1): start/shutdown/
 * force-stop atomically claim {@code vms.pending_power_action} (CAS on
 * "no action in flight AND status is an allowed source") before enqueuing the
 * worker, so two rapid duplicates get exactly one 202 and one 409. The worker
 * ({@link VmPowerJobs}) releases the claim on every exit path, a crashed
 * worker's stale claim is freed by {@code StaleTaskRecoveryJob}, and the status
 * poller skips claimed VMs. Reboot serializes through its visible
 * {@code REBOOTING} transition instead of the claim column, so a force-stop can
 * still interrupt a hung reboot and the poller can converge a crashed one.</p>
 */
@Service
public class VmLifecycleService {

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final JobScheduler jobScheduler;
    private final VmPowerJobs vmPowerJobs;

    public VmLifecycleService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            JobScheduler jobScheduler, VmPowerJobs vmPowerJobs) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.jobScheduler = jobScheduler;
        this.vmPowerJobs = vmPowerJobs;
    }

    @Transactional
    public MessageResponse start(AuthenticatedUser actor, long vmId) {
        requireMemberControllableVm(actor, vmId);
        claimPowerAction(vmId, PowerAction.START, List.of(VmStatus.STOPPED),
                "STOPPED 상태의 VM만 시작할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.start(vmId, actorId));
        return new MessageResponse("VM 시작 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse shutdown(AuthenticatedUser actor, long vmId) {
        requireMemberControllableVm(actor, vmId);
        claimPowerAction(vmId, PowerAction.SHUTDOWN, List.of(VmStatus.RUNNING),
                "RUNNING 상태의 VM만 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.shutdown(vmId, actorId));
        return new MessageResponse("VM 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse reboot(AuthenticatedUser actor, long vmId) {
        requireMemberControllableVm(actor, vmId);
        // Intent is visible immediately (and force-stop can target a hung
        // reboot); the CAS loses to a concurrent transition OR a live claim → 409.
        if (vmRepository.claimReboot(vmId, VmStatus.RUNNING, VmStatus.REBOOTING, Instant.now()) == 0) {
            throw powerConflict(vmId, "RUNNING 상태의 VM만 재부팅할 수 있습니다.");
        }
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.reboot(vmId, actorId));
        return new MessageResponse("VM 재부팅 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse forceStop(AuthenticatedUser actor, long vmId) {
        requireMemberControllableVm(actor, vmId);
        claimPowerAction(vmId, PowerAction.FORCE_STOP,
                List.of(VmStatus.RUNNING, VmStatus.REBOOTING),
                "RUNNING 또는 REBOOTING 상태의 VM만 강제 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.forceStop(vmId, actorId));
        return new MessageResponse("VM 강제 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
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

    /**
     * Resolves the VM for a power op: unknown id and non-member both answer
     * 404 (masking), a member below MEMBER answers 403.
     */
    private Vm requireMemberControllableVm(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmLifecycleService::vmNotFound);
        GroupMemberRole role = groupMemberRepository
                .findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(VmLifecycleService::vmNotFound);
        if (role == GroupMemberRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "VM을 제어할 권한이 없습니다", "그룹의 MEMBER 이상만 VM 전원을 제어할 수 있습니다.");
        }
        return vm;
    }

    /**
     * Builds the 409 for a failed claim/transition: an in-flight action reads
     * as "already processing", otherwise the per-op invalid-state message with
     * the (re-read) current status. Both use {@code VM_INVALID_STATE} for
     * consistency with the existing power 409s.
     */
    private ApiException powerConflict(long vmId, String invalidStateDetail) {
        Vm current = vmRepository.findById(vmId).orElseThrow(VmLifecycleService::vmNotFound);
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

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }

    /**
     * JobRunr's storage provider commits with its own connection, so an
     * in-transaction enqueue could run before the intent row is visible or
     * survive a rollback — enqueue after commit instead (same trade-off as
     * ApprovalService: a crash in the tiny window loses the job; the status
     * poller/reconciler surfaces the drift).
     */
    private void enqueueAfterCommit(JobLambda job) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(job);
            }
        });
    }
}
