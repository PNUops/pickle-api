package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmDetail} (= VmSummary + org/template/access fields).
 * {@code ipAddress} is the live allocation (null before the pipeline's IP
 * step or after release); {@code provisioning} mirrors the VM's most recent
 * async task; {@code initialPasswordAvailable} says whether the one-shot
 * password is still viewable.
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
        ProvisioningTaskView provisioning,
        boolean initialPasswordAvailable,
        Instant updatedAt) {

    public static VmDetailResponse from(Vm vm, String ipAddress, ProvisioningTaskView provisioning) {
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), vm.getRequestId(),
                vm.getStatusDetail(), vm.getCreatedAt(), vm.getOrgId(), vm.getTemplateId(),
                ipAddress, vm.getSshUsername(), vm.getStartDate(), vm.getEndDate(),
                provisioning, vm.getInitialPassword() != null, vm.getUpdatedAt());
    }
}
