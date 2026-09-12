package kr.ac.pusan.pickle.provisioning;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** A timeout is not task cancellation. Every outstanding remote dispatch fences device work. */
@Service
public class VmPowerOperationGuard {
    private final JdbcTemplate jdbc;
    private final VmRepository vms;
    private final NodeRepository nodes;
    private final ProxmoxClient proxmox;
    private final TransactionTemplate tx;
    private final Clock clock;
    public VmPowerOperationGuard(JdbcTemplate jdbc, VmRepository vms, NodeRepository nodes,
            ProxmoxClient proxmox, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc; this.vms = vms; this.nodes = nodes; this.proxmox = proxmox; this.tx = tx; this.clock = clock;
    }
    public String dispatch(long vmId, UUID operation, Supplier<String> command) {
        UUID dispatch = UUID.randomUUID();
        tx.executeWithoutResult(ignored -> {
            var owners = jdbc.queryForList("select power_operation_id from vms where id = ? for update", UUID.class, vmId);
            if (owners.isEmpty() || !operation.equals(owners.getFirst())) { throw new IllegalStateException("전원 작업 소유권이 바뀌었습니다."); }
            jdbc.update("insert into vm_power_dispatches(id,operation_id,vm_id) values (?,?,?)", dispatch, operation, vmId);
        });
        String upid = command.get();
        if (upid == null || upid.isBlank()) { throw new IllegalStateException("원격 전원 작업의 접수 결과를 확인할 수 없습니다."); }
        // Persist even after an explicit force-stop superseded this operation.
        // That newer owner must still know whether our remote task has finished.
        if (jdbc.update("update vm_power_dispatches set task_upid=?,dispatch_pending=false,updated_at=? where id=? and dispatch_pending=true",
                upid, Timestamp.from(clock.instant()), dispatch) != 1) { throw new IllegalStateException("원격 전원 작업 기록이 변경되었습니다."); }
        return upid;
    }
    public void finish(long vmId, UUID operation) {
        var vm = vms.findById(vmId).orElse(null);
        if (vm == null) { return; }
        var node = nodes.findById(vm.getNodeId()).orElse(null);
        if (node == null) { return; }
        var pending = jdbc.query("select id,task_upid,dispatch_pending from vm_power_dispatches where operation_id=? and terminal=false",
                (rs, row) -> new Dispatch(rs.getObject("id", UUID.class), rs.getString("task_upid"), rs.getBoolean("dispatch_pending")), operation);
        for (Dispatch dispatch : pending) {
            if (dispatch.pending() || dispatch.upid() == null) { return; }
            try {
                if (!proxmox.taskStatus(node.getApiHost(), node.getName(), dispatch.upid()).isStopped()) { return; }
            } catch (RuntimeException e) { return; }
            jdbc.update("update vm_power_dispatches set terminal=true,updated_at=? where id=?", Timestamp.from(clock.instant()), dispatch.id());
        }
        // The UUID prevents an old worker from freeing the force-stop that replaced it.
        vms.clearPowerWorker(vmId, operation);
    }
    public void recover() {
        var attempts = jdbc.query("select distinct vm_id,operation_id from vm_power_dispatches where terminal=false and updated_at < ?",
                (rs, row) -> new Owned(rs.getLong("vm_id"), rs.getObject("operation_id", UUID.class)),
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(10))));
        for (Owned owned : attempts) { finish(owned.vmId(), owned.operation()); }
    }
    private record Dispatch(UUID id, String upid, boolean pending) {}
    private record Owned(long vmId, UUID operation) {}
}
