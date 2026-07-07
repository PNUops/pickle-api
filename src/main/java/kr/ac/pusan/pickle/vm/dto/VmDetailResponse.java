package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmDetail} (= VmSummary + org/template/access fields).
 * {@code ipAddress} stays null in M2 — IPAM lands with the M3 pipeline.
 */
public record VmDetailResponse(
        Long id,
        String name,
        String hostname,
        VmStatus status,
        int vcpu,
        int memoryMb,
        int diskGb,
        Long groupId,
        Long requestId,
        String statusDetail,
        Instant createdAt,
        Long orgId,
        Long templateId,
        String ipAddress,
        String sshUsername,
        LocalDate startDate,
        LocalDate endDate,
        Instant updatedAt) {

    public static VmDetailResponse from(Vm vm) {
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), vm.getRequestId(),
                vm.getStatusDetail(), vm.getCreatedAt(), vm.getOrgId(), vm.getTemplateId(),
                null, vm.getSshUsername(), vm.getStartDate(), vm.getEndDate(), vm.getUpdatedAt());
    }
}
