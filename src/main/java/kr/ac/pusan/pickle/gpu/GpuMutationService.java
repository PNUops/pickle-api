package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.common.error.ErrorCodes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.gpu.dto.GpuAllocationView;
import kr.ac.pusan.pickle.gpu.dto.GpuAttachmentOption;
import kr.ac.pusan.pickle.gpu.dto.GpuView;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable intent is committed before a device operation is enqueued. */
@Service
public class GpuMutationService {
    private final GpuStore store;
    private final GpuQueryService query;
    private final VmAccessService vmAccess;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final ResourceAccessResolver access;
    private final GpuAttachmentReadiness readiness;
    private final GpuNotices notices;
    private final AuditService audit;
    private final Clock clock;
    private final JobScheduler jobs;
    private final GpuOperationJob operations;

    public GpuMutationService(GpuStore store, GpuQueryService query, VmAccessService vmAccess,
            VmRepository vms, NodeRepository nodes, ResourceAccessResolver access, GpuAttachmentReadiness readiness,
            GpuNotices notices, AuditService audit, Clock clock, JobScheduler jobs, GpuOperationJob operations) {
        this.store = store; this.query = query; this.vmAccess = vmAccess; this.vms = vms; this.nodes = nodes;
        this.access = access; this.readiness = readiness; this.notices = notices; this.audit = audit;
        this.clock = clock; this.jobs = jobs; this.operations = operations;
    }

    @Transactional(readOnly = true)
    public List<GpuAttachmentOption> options(AuthenticatedUser actor, UUID id) {
        GpuAllocation a = query.requireVisible(actor, id);
        boolean editor = query.standing(actor, a).atLeast(ResourceRole.EDITOR);
        return vms.findByWorkspaceId(a.workspaceId(), Pageable.unpaged()).stream()
                .filter(vm -> access.standing(ResourceType.VM, vm.getId(), vm.getWorkspaceId(), actor.id()).role() != null)
                .map(vm -> {
                    String reason = !editor || !vmAccess.of(vm, actor.id()).atLeast(ResourceRole.EDITOR)
                            ? "GPU와 가상머신 모두 편집자 이상의 권한이 필요합니다." : readiness.blockingReason(a, vm);
                    return new GpuAttachmentOption(vm.getPublicId(), vm.getName(), nodes.findById(vm.getNodeId()).orElseThrow().getPublicId(), reason == null, reason);
                }).toList();
    }

    @Transactional
    public GpuAllocationView attach(AuthenticatedUser actor, UUID id, UUID vmId, String ip) {
        GpuAllocation a = store.lock(requireEditor(actor, id).id());
        Vm vm = vmAccess.of(actor, vmId).requireAtLeast(ResourceRole.EDITOR, "권한이 없습니다", "가상머신 편집 권한이 필요합니다.");
        if (a.workspaceId() != vm.getWorkspaceId()) { throw GpuErrors.invalid("vmId", "같은 워크스페이스의 가상머신을 선택해 주세요."); }
        if (a.leaseEndsAt() == null || !a.leaseEndsAt().isAfter(clock.instant())) {
            throw GpuErrors.conflict(ErrorCodes.GPU_LEASE_ENDED, "GPU 임대 기간이 종료되었습니다.");
        }
        String reason = readiness.blockingReason(a, vm);
        if (reason != null) { throw GpuErrors.conflict(ErrorCodes.GPU_ATTACHMENT_NOT_READY, reason); }
        beginOperation(a, vm, "ATTACH", actor.id(), false);
        record(actor, a, "gpu.attach", Map.of("vmId", vmId), ip);
        return currentView(actor, a, false);
    }

    @Transactional
    public GpuAllocationView detach(AuthenticatedUser actor, UUID id, String ip) {
        GpuAllocation a = store.lock(requireEditor(actor, id).id());
        if (a.status() != GpuAllocationStatus.ALLOCATED || a.connectionStatus() != GpuConnectionStatus.ATTACHED) {
            throw GpuErrors.conflict(ErrorCodes.GPU_INVALID_STATE, "연결된 GPU만 연결 해제할 수 있습니다.");
        }
        Vm vm = vms.findById(a.vmId()).orElseThrow(GpuErrors::notFound);
        vmAccess.of(vm, actor.id()).requireAtLeast(ResourceRole.EDITOR, "권한이 없습니다", "가상머신 편집 권한이 필요합니다.");
        beginOperation(a, vm, "DETACH", actor.id(), false);
        record(actor, a, "gpu.detach", Map.of(), ip);
        return currentView(actor, a, false);
    }

    @Transactional
    public GpuAllocationView release(AuthenticatedUser actor, UUID id, String ip) {
        GpuAllocation a = store.allocation(id).orElseThrow(GpuErrors::notFound);
        var standing = query.standing(actor, a);
        if (!standing.manages()) { standing.requireVisible(GpuResourceAdapter.MESSAGES); throw GpuErrors.forbidden(); }
        a = store.lock(a.id());
        releaseLocked(a, GpuReleaseReason.USER_RELEASE, actor.id(), false);
        record(actor, a, "gpu.release", Map.of(), ip);
        return currentView(actor, a, false);
    }

    @Transactional
    public GpuAllocationView reclaim(AuthenticatedUser actor, UUID id, String reason, String ip) {
        GpuAllocation a = store.allocation(id).orElseThrow(GpuErrors::notFound);
        requireAdminWrite(actor, a);
        a = store.lock(a.id());
        releaseLocked(a, GpuReleaseReason.ADMIN_RECLAIM, actor.id(), true);
        record(actor, a, "gpu.reclaim", Map.of("reason", reason), ip);
        return currentView(actor, a, true);
    }

    @Transactional
    public void expire(long id) {
        GpuAllocation a = store.lock(id);
        if ((a.status() == GpuAllocationStatus.ALLOCATED || a.status() == GpuAllocationStatus.RELEASING) && a.leaseEndsAt() != null && !a.leaseEndsAt().isAfter(clock.instant())) {
            releaseLocked(a, GpuReleaseReason.LEASE_EXPIRED, null, true);
        }
    }

    private void releaseLocked(GpuAllocation a, GpuReleaseReason reason, Long actorId, boolean admin) {
        if (a.releaseReason() != null) { reason = a.releaseReason(); }
        if (a.status() == GpuAllocationStatus.RELEASED || a.status() == GpuAllocationStatus.CANCELED) { return; }
        if (a.connectionStatus() == GpuConnectionStatus.ERROR || a.operationId() != null) {
            throw GpuErrors.conflict(ErrorCodes.GPU_OPERATION_IN_PROGRESS, "GPU 작업 상태를 먼저 확인해야 합니다. 점유는 유지됩니다.");
        }
        Instant now = clock.instant();
        if (a.connectionStatus() == GpuConnectionStatus.NONE) {
            String status = a.status() == GpuAllocationStatus.QUEUED ? "CANCELED" : "RELEASED";
            store.jdbc().update("update gpu_allocations set status = ?::gpu_allocation_status, release_reason = ?, released_at = ?, updated_at = ? where id = ?",
                    status, reason.name(), Timestamp.from(now), Timestamp.from(now), a.id());
            notices.holders(a, "GPU 반납 완료", "GPU를 반납했습니다. 이용 기록은 남습니다.", "gpu.released:" + a.id());
        } else {
            if (a.connectionStatus() != GpuConnectionStatus.ATTACHED) { throw GpuErrors.conflict(ErrorCodes.GPU_INVALID_STATE, "GPU 연결 작업이 진행 중입니다."); }
            store.jdbc().update("update gpu_allocations set status = 'RELEASING', release_reason = ?, updated_at = ? where id = ?",
                    reason.name(), Timestamp.from(now), a.id());
            beginOperation(a, vms.findById(a.vmId()).orElseThrow(), "RELEASE", actorId, admin);
        }
    }

    @Transactional
    public GpuAllocationView extend(AuthenticatedUser actor, UUID id, int hours, String reason, String ip, boolean admin) {
        GpuAllocation a = admin ? store.allocation(id).orElseThrow(GpuErrors::notFound) : requireEditor(actor, id);
        if (admin) { requireAdminWrite(actor, a); if (reason == null || reason.isBlank()) { throw GpuErrors.invalid("reason", "연장 사유를 입력해 주세요."); } }
        a = store.lock(a.id());
        if (a.status() != GpuAllocationStatus.ALLOCATED || !a.leaseEndsAt().isAfter(clock.instant())) { throw GpuErrors.conflict(ErrorCodes.GPU_INVALID_STATE, "진행 중인 임대만 연장할 수 있습니다."); }
        if (!admin && Boolean.TRUE.equals(store.jdbc().queryForObject("select exists(select 1 from gpu_allocations where status = 'QUEUED')", Boolean.class))) {
            throw GpuErrors.conflict(ErrorCodes.GPU_EXTENSION_BLOCKED, "대기 중인 신청이 있어 연장할 수 없습니다.");
        }
        Instant until = a.leaseEndsAt().plus(Duration.ofHours(hours));
        if (a.grantedEndDate() != null && until.isAfter(a.grantedEndDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant())) {
            throw GpuErrors.conflict(ErrorCodes.GPU_EXTENSION_BLOCKED, "승인된 사용 종료일을 넘겨 연장할 수 없습니다.");
        }
        int total;
        try { total = Math.addExact(a.grantedLeaseHours(), hours); } catch (ArithmeticException e) { throw GpuErrors.invalid("hours", "입력한 시간이 너무 큽니다."); }
        store.jdbc().update("update gpu_allocations set granted_lease_hours = ?, lease_ends_at = ?, updated_at = ? where id = ?", total, Timestamp.from(until), Timestamp.from(clock.instant()), a.id());
        record(actor, a, "gpu.extend", Map.of("hours", hours, "reason", reason == null ? "" : reason, "adminException", admin), ip);
        return currentView(actor, a, admin);
    }

    @Transactional
    public GpuAllocationView priority(AuthenticatedUser actor, UUID id, int priority, String reason, String ip) {
        requireSystemOperator(actor);
        store.jdbc().execute("select pg_advisory_xact_lock(734891206)");
        GpuAllocation a = store.lock(store.allocation(id).orElseThrow(GpuErrors::notFound).id());
        if (a.status() != GpuAllocationStatus.QUEUED) { throw GpuErrors.conflict(ErrorCodes.GPU_INVALID_STATE, "대기 중인 할당의 우선순위만 바꿀 수 있습니다."); }
        store.jdbc().update("update gpu_allocations set priority = ?, updated_at = ? where id = ?", priority, Timestamp.from(clock.instant()), a.id());
        record(actor, a, "gpu.priority", Map.of("priority", priority, "reason", reason), ip);
        return currentView(actor, a, true);
    }

    @Transactional
    public GpuView updateGpu(AuthenticatedUser actor, UUID id, GpuStatus status, String reason, String ip) {
        requireSystemOperator(actor);
        Gpu gpu = store.gpu(id).orElseThrow(GpuErrors::notFound);
        store.jdbc().update("update gpus set status = ?::gpu_status, updated_at = ? where id = ?", status.name(), Timestamp.from(clock.instant()), gpu.id());
        audit.recordAfterCommit(actor.id(), actor.role().name(), "gpu.inventory.update", "gpu", id, Map.of("status", status.name(), "reason", reason), ip);
        return query.gpuView(store.gpu(id).orElseThrow());
    }

    /** Deletion resumes only after the GPU is confirmed absent; the lease survives. */
    @Transactional
    public boolean prepareVmDeletion(long vmId) { return prepareVmDeletion(vmId, false); }

    @Transactional
    public boolean prepareVmDeletion(long vmId, boolean allowEarlySelf) {
        var deletingVm = vms.findById(vmId).orElse(null);
        if (deletingVm == null || deletingVm.getDeleteKind() == null || deletingVm.getDeleteScheduledFor() == null) { return false; }
        if (deletingVm.getDeleteScheduledFor().isAfter(clock.instant())
                && !(allowEarlySelf && deletingVm.getDeleteKind().name().equals("SELF"))) { return false; }
        var allocation = store.all().stream().filter(a -> a.vmId() != null && a.vmId() == vmId && a.connectionStatus() != GpuConnectionStatus.NONE).findFirst();
        if (allocation.isEmpty()) {
            return !Boolean.TRUE.equals(store.jdbc().queryForObject("select power_operation_id is not null or exists(select 1 from vm_power_dispatches d where d.vm_id=vms.id and d.terminal=false) from vms where id = ?", Boolean.class, vmId));
        }
        GpuAllocation a = store.lock(allocation.get().id());
        if (a.operationId() != null || a.connectionStatus() == GpuConnectionStatus.ERROR) { return false; }
        if (a.connectionStatus() == GpuConnectionStatus.ATTACHED) {
            beginOperation(a, vms.findById(vmId).orElseThrow(), "VM_DELETE", null, true);
        }
        return false;
    }

    private void beginOperation(GpuAllocation a, Vm vm, String kind, Long actorId, boolean admin) {
        UUID operation = UUID.randomUUID();
        Instant now = clock.instant();
        int claimed = store.jdbc().update("""
                update vms set pending_power_action = ?, pending_power_action_at = ?, power_operation_id = ?
                 where id = ? and pending_power_action is null and power_operation_id is null
                   and (status in ('RUNNING','STOPPED') or (? = 'VM_DELETE' and status = 'DELETING'))
                   and (delete_kind is null or ? = 'VM_DELETE')
                   and not exists(select 1 from vm_power_dispatches d where d.vm_id = vms.id and d.terminal = false)
                """, "GPU_" + kind, Timestamp.from(now), operation, vm.getId(), kind, kind);
        if (claimed != 1) { throw GpuErrors.conflict(ErrorCodes.GPU_VM_BUSY, "가상머신의 다른 작업이 진행 중입니다."); }
        store.jdbc().update("""
                insert into gpu_operations(id, allocation_id, vm_id, node_id, kind, was_running, actor_id, admin_action,
                    delete_requested_at, delete_scheduled_for, delete_kind)
                 values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operation, a.id(), vm.getId(), vm.getNodeId(), kind, vm.getStatus() == VmStatus.RUNNING && !"VM_DELETE".equals(kind), actorId, admin,
                    vm.getDeleteRequestedAt() == null ? null : Timestamp.from(vm.getDeleteRequestedAt()),
                    vm.getDeleteScheduledFor() == null ? null : Timestamp.from(vm.getDeleteScheduledFor()),
                    vm.getDeleteKind() == null ? null : vm.getDeleteKind().name());
        store.jdbc().update("update gpu_allocations set vm_id = ?, connection_status = ?::gpu_connection_status, operation_id = ?, error = null, updated_at = ? where id = ?",
                vm.getId(), "ATTACH".equals(kind) ? "ATTACHING" : "DETACHING", operation, Timestamp.from(now), a.id());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { jobs.enqueue(() -> operations.run(operation)); } catch (RuntimeException e) { /* The recurring scan resumes committed intent. */ }
            }
        });
    }

    private GpuAllocation requireEditor(AuthenticatedUser actor, UUID id) {
        GpuAllocation a = query.requireVisible(actor, id);
        if (!query.standing(actor, a).atLeast(ResourceRole.EDITOR)) { throw GpuErrors.forbidden(); }
        return a;
    }
    private GpuAllocationView currentView(AuthenticatedUser actor, GpuAllocation a, boolean admin) {
        return query.view(actor, store.allocation(a.id()).orElseThrow(), admin);
    }
    private void record(AuthenticatedUser actor, GpuAllocation a, String action, Map<String, Object> data, String ip) {
        audit.recordAfterCommit(actor.id(), actor.role().name(), action, "gpu_allocation", a.publicId(), data, ip);
    }
    static void requireSystemOperator(AuthenticatedUser actor) {
        if (actor.role() != UserRole.SYS_MANAGER && actor.role() != UserRole.SYS_ADMIN) { throw GpuErrors.forbidden(); }
    }
    static void requireAdminWrite(AuthenticatedUser actor, GpuAllocation a) {
        if (actor.role() == UserRole.SYS_MANAGER || actor.role() == UserRole.SYS_ADMIN || actor.administers(a.orgId())) { return; }
        if (!actor.role().isSysTier() && !actor.reads(a.orgId())) { throw GpuErrors.notFound(); }
        throw GpuErrors.forbidden();
    }
}
