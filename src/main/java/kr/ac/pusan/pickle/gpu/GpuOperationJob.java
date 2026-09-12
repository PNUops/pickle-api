package kr.ac.pusan.pickle.gpu;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmActorKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-attempt device operations; uncertain outcomes are never retried blindly. */
@Component
public class GpuOperationJob {
    private static final Logger log = LoggerFactory.getLogger(GpuOperationJob.class);
    private final GpuStore store;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final ProxmoxClient proxmox;
    private final GpuNotices notices;
    private final VmEventRepository events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final kr.ac.pusan.pickle.vmsettings.VmSettingsService vmSettings;
    private final GpuGuestReadiness guestReadiness;

    public GpuOperationJob(GpuStore store, VmRepository vms, NodeRepository nodes, ProxmoxClient proxmox,
            GpuNotices notices, VmEventRepository events, TransactionTemplate tx, Clock clock,
            kr.ac.pusan.pickle.vmsettings.VmSettingsService vmSettings, GpuGuestReadiness guestReadiness) {
        this.store = store; this.vms = vms; this.nodes = nodes; this.proxmox = proxmox; this.notices = notices;
        this.events = events; this.tx = tx; this.clock = clock; this.vmSettings = vmSettings; this.guestReadiness = guestReadiness;
    }

    @Recurring(id = "gpu-operation-recovery", interval = "PT1M")
    @Job(name = "gpu-operation-recovery", retries = 0)
    public void recover() {
        for (UUID id : store.jdbc().queryForList("select id from gpu_operations where phase = 'PENDING' order by created_at", UUID.class)) { run(id); }
        for (UUID id : store.jdbc().queryForList("select id from gpu_operations where phase = 'RUNNING' and updated_at < ?", UUID.class,
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30))))) {
            fail(id, "작업이 중단되었을 수 있습니다. 장치 상태를 확인할 때까지 점유와 가상머신 잠금을 유지합니다.");
        }
    }

    @Job(name = "gpu-operation %0", retries = 0)
    public void run(UUID id) {
        UUID worker = UUID.randomUUID();
        int won = store.jdbc().update("update gpu_operations set phase = 'RUNNING', worker_id = ?, updated_at = ? where id = ? and phase = 'PENDING'",
                worker, Timestamp.from(clock.instant()), id);
        if (won == 0) { return; }
        try {
            Operation op = load(id);
            if ("VM_DELETE".equals(op.kind()) && !deletionStillRequested(op)) {
                cancelUnstartedDelete(op, worker);
                return;
            }
            GpuAllocation a = store.allocation(op.allocationId()).orElseThrow();
            if ("ATTACH".equals(op.kind()) && (a.status() != GpuAllocationStatus.ALLOCATED || !a.leaseEndsAt().isAfter(clock.instant()))) {
                cancelUnstartedAttach(op, worker);
                return;
            }
            Gpu gpu = store.gpu(a.gpuId()).orElseThrow();
            Vm vm = vms.findById(op.vmId()).orElseThrow();
            Node node = nodes.findById(op.nodeId()).orElseThrow();
            if (vm.getProxmoxVmid() == null || vm.getNodeId() != gpu.nodeId() || op.nodeId() != gpu.nodeId()) {
                throw new IllegalStateException("GPU와 가상머신의 실제 노드가 일치하지 않습니다.");
            }
            checkOwnership(op, worker);
            if ("ATTACH".equals(op.kind()) && guestReadiness.blockingReason(vm) != null) {
                throw new IllegalStateException("게스트 GPU 준비 상태가 변경되었습니다.");
            }
            GpuDeviceState state = inspect(node, vm);
            state.requireKnown(gpu);
            boolean attach = "ATTACH".equals(op.kind());
            if (attach && !state.absent(gpu)) { throw new IllegalStateException("장착 전에 GPU가 이미 연결되어 있습니다."); }
            if (!attach && !state.attached(gpu)) { throw new IllegalStateException("해제할 GPU의 연결 상태가 예상과 다릅니다."); }
            if (!"VM_DELETE".equals(op.kind()) && op.wasRunning() == state.stopped()) {
                throw new IllegalStateException("가상머신의 전원 상태가 연결 요청 후 바뀌었습니다.");
            }
            advance(op, worker, "SHUTDOWN");
            if (!state.stopped()) {
                markDispatch(op, worker);
                String upid = proxmox.shutdown(node.getApiHost(), node.getName(), vm.getProxmoxVmid(), 120);
                saveUpid(op, worker, upid);
                proxmox.awaitTask(node.getApiHost(), node.getName(), upid, Duration.ofMinutes(3));
            }
            checkOwnership(op, worker);
            state = inspect(node, vm);
            state.requireKnown(gpu);
            if (!state.stopped()) { throw new IllegalStateException("가상머신이 정상 종료되지 않았습니다. 강제 종료하지 않습니다."); }
            advance(op, worker, "WRITE_CONFIG");
            markDispatch(op, worker);
            proxmox.config(node.getApiHost(), node.getName(), vm.getProxmoxVmid(), attach
                    ? Map.of(gpu.hostpciSlot(), "mapping=" + gpu.mappingName() + ",rombar=0")
                    : Map.of("delete", gpu.hostpciSlot()));
            finishSynchronousDispatch(op, worker);
            checkOwnership(op, worker);
            state = inspect(node, vm);
            state.requireKnown(gpu);
            if (!state.stopped() || (attach ? !state.attached(gpu) : !state.absent(gpu))) {
                throw new IllegalStateException("GPU 설정이 실제 가상머신에 적용되지 않았습니다.");
            }
            advance(op, worker, "VERIFIED_CONFIG");
            if (op.wasRunning()) {
                advance(op, worker, "START");
                markDispatch(op, worker);
                String upid = proxmox.start(node.getApiHost(), node.getName(), vm.getProxmoxVmid());
                saveUpid(op, worker, upid);
                proxmox.awaitTask(node.getApiHost(), node.getName(), upid, Duration.ofMinutes(8));
                checkOwnership(op, worker);
                state = inspect(node, vm);
                state.requireKnown(gpu);
                if (state.stopped() || (attach ? !state.attached(gpu) : !state.absent(gpu))) {
                    throw new IllegalStateException("가상머신이 원하는 GPU 연결 상태로 기동되지 않았습니다. 반복 기동하지 않습니다.");
                }
            }
            complete(op, worker, attach);
        } catch (RuntimeException e) {
            log.warn("GPU operation {} stopped: {}", id, e.toString());
            rollbackVerifiedAttach(id, worker);
            fail(id, "GPU 작업이 완료되지 않았습니다. 관리자 상태 확인이 필요합니다.");
        }
    }

    /** Only a known stopped VM with our exact applied mapping can be rolled back. */
    private void rollbackVerifiedAttach(UUID id, UUID worker) {
        try {
            Operation op = load(id);
            if (!"ATTACH".equals(op.kind()) || !List.of("VERIFIED_CONFIG", "START").contains(op.step())) { return; }
            checkOwnership(op, worker);
            GpuAllocation a = store.allocation(op.allocationId()).orElseThrow();
            Gpu gpu = store.gpu(a.gpuId()).orElseThrow();
            Vm vm = vms.findById(op.vmId()).orElseThrow();
            Node node = nodes.findById(op.nodeId()).orElseThrow();
            requireRemoteTaskFinished(op.id(), node);
            GpuDeviceState state = inspect(node, vm);
            state.requireKnown(gpu);
            if (state.stopped() && state.attached(gpu)) {
                markDispatch(op, worker);
                proxmox.config(node.getApiHost(), node.getName(), vm.getProxmoxVmid(), Map.of("delete", gpu.hostpciSlot()));
                finishSynchronousDispatch(op, worker);
            }
        } catch (RuntimeException e) { log.warn("GPU rollback remains uncertain for {}", id); }
    }

    public GpuDeviceState inspect(Node node, Vm vm) {
        Map<String, Object> status = proxmox.currentVmStatus(node.getApiHost(), node.getName(), vm.getProxmoxVmid());
        return new GpuDeviceState(status == null ? null : String.valueOf(status.get("status")),
                proxmox.currentVmConfig(node.getApiHost(), node.getName(), vm.getProxmoxVmid()),
                proxmox.pendingVmConfig(node.getApiHost(), node.getName(), vm.getProxmoxVmid()));
    }

    private void complete(Operation op, UUID worker, boolean attached) {
        tx.executeWithoutResult(ignored -> {
            GpuAllocation a = store.lock(op.allocationId());
            lockOperation(op.id());
            checkOwnership(op, worker);
            Instant now = clock.instant();
            boolean released = "RELEASE".equals(op.kind());
            int allocationUpdated = store.jdbc().update("""
                    update gpu_allocations set connection_status = ?::gpu_connection_status, vm_id = ?,
                       status = ?::gpu_allocation_status, operation_id = null, error = null,
                       unattached_since = ?, attached_at = ?, released_at = ?, updated_at = ? where id = ? and operation_id = ?
                    """, attached ? "ATTACHED" : "NONE", attached ? op.vmId() : null,
                    released ? "RELEASED" : a.status().name(), attached || released ? null : Timestamp.from(now),
                    attached ? Timestamp.from(now) : null, released ? Timestamp.from(now) : null, Timestamp.from(now), a.id(), op.id());
            int operationUpdated = store.jdbc().update("update gpu_operations set phase = 'DONE', step = 'DONE', updated_at = ? where id = ? and worker_id = ? and phase = 'RUNNING'",
                    Timestamp.from(now), op.id(), worker);
            if (allocationUpdated != 1 || operationUpdated != 1) { throw new IllegalStateException("GPU 완료 처리의 소유권이 바뀌었습니다."); }
            releaseClaim(op);
            if (!"VM_DELETE".equals(op.kind())) {
                store.jdbc().update("update vms set status = ?::vm_status, updated_at = ? where id = ? and delete_kind is null",
                        op.wasRunning() ? "RUNNING" : "STOPPED", Timestamp.from(now), op.vmId());
            }
            events.save(new VmEvent(op.vmId(), attached ? VmEventType.GPU_ATTACH : VmEventType.GPU_DETACH, op.actorId(),
                    op.actorId() == null ? VmActorKind.SYSTEM : op.adminAction() ? VmActorKind.ADMIN : VmActorKind.MEMBER,
                    attached ? "GPU 연결 완료" : "GPU 연결 해제 완료"));
            notices.holders(a, attached ? "GPU 연결 완료" : "GPU 연결 해제 완료",
                    released ? "GPU를 반납했습니다." : attached ? "GPU를 가상머신에 연결했습니다." : "가상머신 연결을 해제했습니다. GPU 임대는 유지됩니다.", "gpu.operation:" + op.id());
        });
    }

    private void fail(UUID id, String message) {
        tx.executeWithoutResult(ignored -> {
            Operation op = load(id);
            store.lock(op.allocationId());
            lockOperation(id);
            int changed = store.jdbc().update("update gpu_operations set phase = 'ERROR', error = ?, updated_at = ? where id = ? and phase = 'RUNNING'", message, Timestamp.from(clock.instant()), id);
            if (changed == 0) { return; }
            store.jdbc().update("update gpu_allocations set connection_status = 'ERROR', error = ?, updated_at = ? where id = ? and operation_id = ?",
                    message, Timestamp.from(clock.instant()), op.allocationId(), id);
            notices.administrators(message, "gpu.operation.error:" + id);
        });
    }

    private void markDispatch(Operation op, UUID worker) {
        tx.executeWithoutResult(ignored -> {
            store.lock(op.allocationId());
            lockOperation(op.id());
            checkOwnership(op, worker);
            int changed = store.jdbc().update("update gpu_operations set remote_dispatch_pending = true, task_upid = null, updated_at = ? where id = ? and worker_id = ? and phase = 'RUNNING'", Timestamp.from(clock.instant()), op.id(), worker);
            if (changed != 1) { throw new IllegalStateException("원격 명령의 작업 소유권이 바뀌었습니다."); }
        });
    }
    private void finishSynchronousDispatch(Operation op, UUID worker) {
        int changed = store.jdbc().update("update gpu_operations set remote_dispatch_pending = false, updated_at = ? where id = ? and worker_id = ? and phase in ('RUNNING','ERROR') and remote_dispatch_pending = true", Timestamp.from(clock.instant()), op.id(), worker);
        if (changed != 1) { throw new IllegalStateException("원격 명령 결과를 확정할 수 없습니다."); }
    }
    private void saveUpid(Operation op, UUID worker, String upid) {
        if (upid == null || upid.isBlank()) { throw new IllegalStateException("원격 작업 식별자가 없습니다."); }
        int changed = store.jdbc().update("update gpu_operations set task_upid = ?, remote_dispatch_pending = false, updated_at = ? where id = ? and worker_id = ? and phase in ('RUNNING','ERROR') and remote_dispatch_pending = true", upid, Timestamp.from(clock.instant()), op.id(), worker);
        if (changed != 1) { throw new IllegalStateException("원격 작업 결과를 기록할 수 없습니다."); }
    }
    public RemoteProof requireRemoteTaskFinished(UUID operationId, Node node) {
        RemoteProof proof = remoteProof(operationId);
        if (proof.pending()) { throw new IllegalStateException("원격 작업 접수 결과를 확인할 수 없습니다."); }
        if (proof.upid() != null && !proxmox.taskStatus(node.getApiHost(), node.getName(), proof.upid()).isStopped()) {
            throw new IllegalStateException("원격 전원 작업이 아직 진행 중입니다.");
        }
        return proof;
    }
    public void requireUnchangedRemoteProof(UUID id, RemoteProof proof) {
        lockOperation(id);
        if (!proof.equals(remoteProof(id))) { throw new IllegalStateException("상태 확인 중 원격 작업 기록이 변경되었습니다."); }
    }
    private RemoteProof remoteProof(UUID id) {
        return store.jdbc().queryForObject("select task_upid, remote_dispatch_pending, updated_at from gpu_operations where id = ?",
                (rs, row) -> new RemoteProof(rs.getString("task_upid"), rs.getBoolean("remote_dispatch_pending"), GpuStore.instant(rs, "updated_at")), id);
    }
    private void lockOperation(UUID id) { store.jdbc().queryForObject("select id from gpu_operations where id = ? for update", UUID.class, id); }
    public record RemoteProof(String upid, boolean pending, Instant updatedAt) {}
    private void advance(Operation op, UUID worker, String step) {
        checkOwnership(op, worker);
        if (store.jdbc().update("update gpu_operations set step = ?, updated_at = ? where id = ? and worker_id = ? and phase = 'RUNNING'",
                step, Timestamp.from(clock.instant()), op.id(), worker) != 1) { throw new IllegalStateException("GPU 작업 소유권이 바뀌었습니다."); }
    }
    private boolean deletionStillRequested(Operation op) {
        if (vmSettings.bool(op.vmId(), kr.ac.pusan.pickle.vmsettings.VmSettingsService.DELETION_PROTECTION)) { return false; }
        return Boolean.TRUE.equals(store.jdbc().queryForObject("""
                select exists(select 1 from gpu_operations o join vms v on v.id = o.vm_id
                 where o.id = ? and v.delete_kind is not null
                   and v.delete_kind::text = o.delete_kind
                   and v.delete_requested_at is not distinct from o.delete_requested_at
                   and v.delete_scheduled_for is not distinct from o.delete_scheduled_for
                   and (v.delete_kind = 'SELF' or v.delete_scheduled_for <= ?))
                """, Boolean.class, op.id(), Timestamp.from(clock.instant())));
    }
    private void cancelUnstartedAttach(Operation op, UUID worker) {
        tx.executeWithoutResult(ignored -> {
            store.lock(op.allocationId()); lockOperation(op.id()); checkOwnership(op, worker);
            int changed = store.jdbc().update("update gpu_operations set phase = 'DONE', step = 'CANCELED', updated_at = now() where id = ? and worker_id = ? and phase = 'RUNNING' and step = 'CLAIMED'", op.id(), worker);
            if (changed != 1) { throw new IllegalStateException("연결 취소 상태가 바뀌었습니다."); }
            store.jdbc().update("update gpu_allocations set connection_status = 'NONE', vm_id = null, operation_id = null, updated_at = now() where id = ? and operation_id = ?", op.allocationId(), op.id());
            releaseClaim(op);
        });
    }
    private void cancelUnstartedDelete(Operation op, UUID worker) {
        tx.executeWithoutResult(ignored -> {
            store.lock(op.allocationId());
            int won = store.jdbc().update("update gpu_operations set phase = 'DONE', step = 'CANCELED', updated_at = now() where id = ? and worker_id = ? and phase = 'RUNNING' and step = 'CLAIMED'", op.id(), worker);
            if (won == 1) {
                store.jdbc().update("update gpu_allocations set connection_status = 'ATTACHED', operation_id = null, updated_at = now() where id = ? and operation_id = ?", op.allocationId(), op.id());
                releaseClaim(op);
            }
        });
    }
    private void checkOwnership(Operation op, UUID worker) {
        if ("VM_DELETE".equals(op.kind()) && !deletionStillRequested(op)) { throw new IllegalStateException("삭제 의도가 바뀌어 GPU 작업을 중단했습니다."); }
        Boolean owned = store.jdbc().queryForObject("""
                select exists(select 1 from gpu_operations o join vms v on v.id = o.vm_id
                  join gpu_allocations a on a.id = o.allocation_id
                 where o.id = ? and o.worker_id = ? and o.phase = 'RUNNING'
                   and v.power_operation_id = o.id and a.operation_id = o.id)
                """, Boolean.class, op.id(), worker);
        if (!Boolean.TRUE.equals(owned)) { throw new IllegalStateException("GPU 작업 소유권이 바뀌었습니다."); }
    }
    void releaseClaim(Operation op) {
        if (store.jdbc().update("update vms set pending_power_action = null, pending_power_action_at = null, power_operation_id = null where id = ? and power_operation_id = ?",
                op.vmId(), op.id()) != 1) { throw new IllegalStateException("GPU가 소유한 가상머신 잠금을 확인할 수 없습니다."); }
    }
    Operation load(UUID id) {
        return store.jdbc().queryForObject("select * from gpu_operations where id = ?", (rs, row) -> new Operation(
                id, rs.getLong("allocation_id"), rs.getLong("vm_id"), rs.getLong("node_id"), rs.getString("kind"),
                rs.getString("step"), rs.getBoolean("was_running"), rs.getObject("actor_id", Long.class), rs.getBoolean("admin_action")), id);
    }
    record Operation(UUID id, long allocationId, long vmId, long nodeId, String kind, String step, boolean wasRunning, Long actorId, boolean adminAction) {}
}
