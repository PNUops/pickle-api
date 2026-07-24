package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmDetail} (= VmSummary + org/template/access fields
 * plus the lifecycle surface, the publish surface, and the SSH
 * surface: the requester's group role, whether the stored password is available
 * and revealable by the requester, and the SSH gateway host for connect hints).
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
        String orgName,
        String displayName,
        Long requestId,
        String statusDetail,
        Instant createdAt,
        Long orgId,
        Long templateId,
        String ipAddress,
        String sshUsername,
        String sshHost,
        GroupMemberRole myGroupRole,
        LocalDate startDate,
        LocalDate endDate,
        Instant expiryStoppedAt,
        ProvisioningTaskResponse provisioning,
        VmDeletionResponse deletion,
        boolean httpPublishGranted,
        PublicationView publication,
        boolean passwordAvailable,
        boolean passwordRevealAllowed,
        Instant updatedAt) {

    public static VmDetailResponse from(Vm vm, String groupName, String orgName, String displayName,
            String ipAddress, String sshHost, GroupMemberRole myGroupRole,
            boolean passwordRevealAllowed, ProvisioningTaskResponse provisioning,
            boolean httpPublishGranted, PublicationView publication) {
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName, orgName,
                displayName, vm.getRequestId(), vm.getStatusDetail(), vm.getCreatedAt(), vm.getOrgId(),
                vm.getTemplateId(), ipAddress, vm.getSshUsername(), sshHost, myGroupRole,
                vm.getStartDate(), vm.getEndDate(), vm.getExpiryStoppedAt(), provisioning,
                VmDeletionResponse.from(vm), httpPublishGranted, publication,
                vm.getPasswordEnc() != null, passwordRevealAllowed, vm.getUpdatedAt());
    }
}
