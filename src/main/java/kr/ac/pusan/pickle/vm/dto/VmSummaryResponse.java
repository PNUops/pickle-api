package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmSummary}. {@code groupName} is joined for the list
 * view; {@code orgName} and {@code displayName} (VM setting,
 * null when unset) are joined for the console list/admin surfaces.
 */
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
        String orgName,
        String displayName,
        Long requestId,
        String statusDetail,
        LocalDate endDate,
        Instant expiryStoppedAt,
        Instant createdAt) {

    public static VmSummaryResponse from(Vm vm, String groupName, String orgName, String displayName) {
        return new VmSummaryResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName, orgName,
                displayName, vm.getRequestId(), vm.getStatusDetail(), vm.getEndDate(),
                vm.getExpiryStoppedAt(), vm.getCreatedAt());
    }
}
