package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.common.error.ErrorCodes;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.gpu.dto.GpuAllocationView;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Readback can release an operation lock but cannot allocate or release a card. */
@Service
public class GpuReconciliationService {
    private final GpuStore store;
    private final GpuOperationJob operations;
    private final GpuQueryService query;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AuditService audit;
    public GpuReconciliationService(GpuStore store, GpuOperationJob operations, GpuQueryService query,
            VmRepository vms, NodeRepository nodes, TransactionTemplate tx, Clock clock, AuditService audit) {
        this.store = store; this.operations = operations; this.query = query; this.vms = vms; this.nodes = nodes;
        this.tx = tx; this.clock = clock; this.audit = audit;
    }
    public GpuAllocationView reconcile(AuthenticatedUser actor, UUID id, String reason, String ip) {
        GpuMutationService.requireSystemOperator(actor);
        GpuAllocation observed = store.allocation(id).orElseThrow(GpuErrors::notFound);
        if (observed.connectionStatus() != GpuConnectionStatus.ERROR || observed.operationId() == null || observed.vmId() == null) {
            throw GpuErrors.conflict(ErrorCodes.GPU_INVALID_STATE, "오류로 멈춘 GPU 작업만 다시 확인할 수 있습니다.");
        }
        var vm = vms.findById(observed.vmId()).orElseThrow(GpuErrors::notFound);
        var gpu = store.gpu(observed.gpuId()).orElseThrow(GpuErrors::notFound);
        var node = nodes.findById(vm.getNodeId()).orElseThrow(GpuErrors::notFound);
        GpuDeviceState state;
        GpuOperationJob.RemoteProof proof;
        try {
            if (vm.getProxmoxVmid() == null || node.getId() != gpu.nodeId()) { throw new IllegalStateException(); }
            proof = operations.requireRemoteTaskFinished(observed.operationId(), node);
            state = operations.inspect(node, vm);
            state.requireKnown(gpu);
        } catch (RuntimeException e) {
            throw GpuErrors.conflict(ErrorCodes.GPU_STATE_UNCONFIRMED, "실제 연결 상태를 확인하지 못했습니다. GPU 점유와 가상머신 잠금을 유지합니다.");
        }
        boolean attached = state.attached(gpu);
        tx.executeWithoutResult(ignored -> {
            GpuAllocation a = store.lock(observed.id());
            if (!observed.operationId().equals(a.operationId()) || a.connectionStatus() != GpuConnectionStatus.ERROR) {
                throw GpuErrors.conflict(ErrorCodes.GPU_OPERATION_CHANGED, "다른 작업이 상태를 변경했습니다. 다시 확인해 주세요.");
            }
            try { operations.requireUnchangedRemoteProof(a.operationId(), proof); }
            catch (IllegalStateException e) { throw GpuErrors.conflict(ErrorCodes.GPU_STATE_UNCONFIRMED, "상태 확인 중 원격 작업 기록이 바뀌었습니다. 잠금을 유지합니다."); }
            var owners = store.jdbc().queryForList("select power_operation_id from vms where id = ? for update", UUID.class, a.vmId());
            if (owners.isEmpty() || !a.operationId().equals(owners.getFirst())) {
                throw GpuErrors.conflict(ErrorCodes.GPU_OPERATION_CHANGED, "가상머신 작업 소유권이 바뀌었습니다. 현재 상태를 유지합니다.");
            }
            int changed = store.jdbc().update("update gpu_operations set phase = 'DONE', step = 'RECONCILED', updated_at = ? where id = ? and phase = 'ERROR'",
                    Timestamp.from(clock.instant()), a.operationId());
            if (changed != 1) { throw GpuErrors.conflict(ErrorCodes.GPU_OPERATION_IN_PROGRESS, "진행 중인 작업을 확인해야 합니다."); }
            store.jdbc().update("""
                    update gpu_allocations set connection_status = ?::gpu_connection_status, vm_id = ?,
                        operation_id = null, error = null, attached_at = ?, unattached_since = ?, updated_at = ?
                    where id = ?
                    """, attached ? "ATTACHED" : "NONE", attached ? a.vmId() : null,
                    attached ? Timestamp.from(a.attachedAt() == null ? clock.instant() : a.attachedAt()) : null,
                    attached ? null : Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), a.id());
            operations.releaseClaim(operations.load(a.operationId()));
            store.jdbc().update("update vms set status = ?::vm_status, updated_at = ? where id = ? and delete_kind is null",
                    state.stopped() ? "STOPPED" : "RUNNING", Timestamp.from(clock.instant()), vm.getId());
            audit.recordAfterCommit(actor.id(), actor.role().name(), "gpu.reconcile", "gpu_allocation", id,
                    Map.of("reason", reason, "connectionStatus", attached ? "ATTACHED" : "NONE"), ip);
        });
        return query.adminGet(actor, id);
    }
}
