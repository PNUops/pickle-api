package kr.ac.pusan.pickle.gpu;

import java.util.List;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.stereotype.Service;

@Service
public class GpuAttachmentReadiness {
    private final GpuStore store;
    private final GpuGuestReadiness guest;
    private final GpuMigrationReadiness migration;
    public GpuAttachmentReadiness(GpuStore store, GpuGuestReadiness guest, GpuMigrationReadiness migration) {
        this.store = store; this.guest = guest; this.migration = migration;
    }
    public String blockingReason(GpuAllocation allocation, Vm vm) {
        if (allocation.status() != GpuAllocationStatus.ALLOCATED || allocation.connectionStatus() != GpuConnectionStatus.NONE) {
            return "할당된 GPU가 미연결 상태일 때 연결할 수 있어요.";
        }
        if (!List.of(VmStatus.RUNNING, VmStatus.STOPPED).contains(vm.getStatus()) || vm.getDeleteKind() != null) {
            return "현재 상태의 VM에는 GPU를 연결할 수 없어요.";
        }
        if (vm.getPendingPowerAction() != null) { return "VM의 다른 작업이나 현재 상태를 먼저 확인해 주세요."; }
        if (Boolean.TRUE.equals(store.jdbc().queryForObject("select exists(select 1 from gpu_allocations where vm_id = ? and connection_status <> 'NONE')", Boolean.class, vm.getId()))) {
            return "이 VM에는 다른 GPU의 연결 설정이 남아 있어요.";
        }
        if (Boolean.TRUE.equals(store.jdbc().queryForObject("select exists(select 1 from vm_power_dispatches where vm_id=? and terminal=false)", Boolean.class, vm.getId()))) {
            return "이전 전원 작업이 끝났는지 확인해 주세요.";
        }
        Gpu gpu = store.gpu(allocation.gpuId()).orElseThrow();
        if (!"ACTIVE".equals(store.jdbc().queryForObject("select status::text from nodes where id=?", String.class, gpu.nodeId()))) {
            return "GPU가 있는 노드를 현재 사용할 수 없어요.";
        }
        if (gpu.status() != GpuStatus.ACTIVE) { return "GPU가 점검 중이거나 사용 종료 상태예요."; }
        String reason = migration.blockingReason(vm, gpu.nodeId());
        return reason != null ? reason : guest.blockingReason(vm);
    }
}
