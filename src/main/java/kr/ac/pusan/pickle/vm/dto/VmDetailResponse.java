package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmDetail} (= VmSummary + org/template/access fields
 * plus the M3 lifecycle surface and the M4A publish surface: in-flight/last-failed
 * async task, pending deletion, one-shot password availability, the allocated
 * internal IP, whether HTTP publishing was granted, and the current publication).
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
        String groupName,
        Long requestId,
        String statusDetail,
        Instant createdAt,
        Long orgId,
        Long templateId,
        String ipAddress,
        String sshUsername,
        LocalDate startDate,
        LocalDate endDate,
        Instant expiryStoppedAt,
        ProvisioningTaskResponse provisioning,
        VmDeletionResponse deletion,
        boolean httpPublishGranted,
        PublicationView publication,
        boolean initialPasswordAvailable,
        Instant updatedAt) {

    public static VmDetailResponse from(Vm vm, String groupName, String ipAddress,
            ProvisioningTaskResponse provisioning, boolean httpPublishGranted,
            PublicationView publication) {
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName,
                vm.getRequestId(), vm.getStatusDetail(), vm.getCreatedAt(), vm.getOrgId(),
                vm.getTemplateId(), ipAddress, vm.getSshUsername(), vm.getStartDate(),
                vm.getEndDate(), vm.getExpiryStoppedAt(), provisioning, VmDeletionResponse.from(vm),
                httpPublishGranted, publication,
                vm.getInitialPassword() != null, vm.getUpdatedAt());
    }
}
