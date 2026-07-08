package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/** Contract schema {@code VmSummary} ({@code groupName} joined for the list view). */
public record VmSummaryResponse(
        Long id,
        String name,
        String hostname,
        VmStatus status,
        int vcpu,
        int memoryMb,
        int diskGb,
        Long groupId,
        String groupName,
        Long requestId,
        String statusDetail,
        Instant createdAt) {

    public static VmSummaryResponse from(Vm vm, String groupName) {
        return new VmSummaryResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName,
                vm.getRequestId(), vm.getStatusDetail(), vm.getCreatedAt());
    }
}
