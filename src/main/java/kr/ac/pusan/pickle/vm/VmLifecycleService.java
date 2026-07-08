package kr.ac.pusan.pickle.vm;

import java.time.Instant;
import java.util.Set;
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
        Vm vm = requireMemberControllableVm(actor, vmId);
        requireStatus(vm, Set.of(VmStatus.STOPPED), "STOPPED 상태의 VM만 시작할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.start(vmId, actorId));
        return new MessageResponse("VM 시작 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse shutdown(AuthenticatedUser actor, long vmId) {
        Vm vm = requireMemberControllableVm(actor, vmId);
        requireStatus(vm, Set.of(VmStatus.RUNNING), "RUNNING 상태의 VM만 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.shutdown(vmId, actorId));
        return new MessageResponse("VM 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse reboot(AuthenticatedUser actor, long vmId) {
        Vm vm = requireMemberControllableVm(actor, vmId);
        requireStatus(vm, Set.of(VmStatus.RUNNING), "RUNNING 상태의 VM만 재부팅할 수 있습니다.");
        // Intent is visible immediately (and force-stop can target a hung
        // reboot); the CAS loses only to a concurrent transition → 409.
        int updated = vmRepository.transitionStatus(vmId, VmStatus.RUNNING, VmStatus.REBOOTING,
                null, Instant.now());
        if (updated == 0) {
            throw invalidState(vm, "RUNNING 상태의 VM만 재부팅할 수 있습니다.");
        }
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.reboot(vmId, actorId));
        return new MessageResponse("VM 재부팅 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    @Transactional
    public MessageResponse forceStop(AuthenticatedUser actor, long vmId) {
        Vm vm = requireMemberControllableVm(actor, vmId);
        requireStatus(vm, Set.of(VmStatus.RUNNING, VmStatus.REBOOTING),
                "RUNNING 또는 REBOOTING 상태의 VM만 강제 종료할 수 있습니다.");
        long actorId = actor.id();
        enqueueAfterCommit(() -> vmPowerJobs.forceStop(vmId, actorId));
        return new MessageResponse("VM 강제 종료 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
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

    private void requireStatus(Vm vm, Set<VmStatus> allowed, String baseDetail) {
        if (!allowed.contains(vm.getStatus())) {
            throw invalidState(vm, baseDetail);
        }
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
