package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmDetail} (= VmSummary + org/image/access fields
 * plus the lifecycle surface, the publish surface, and the SSH
 * surface: the requester's group role, whether the stored password is available
 * and revealable by the requester, and the SSH gateway host for connect hints).
 * {@code publications} lists every serving domain in id order — empty when the
 * VM is unpublished.
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
        @Nullable String orgName,
        @Nullable String displayName,
        Long requestId,
        @Nullable String statusDetail,
        boolean sshGatewayBlocked,
        Instant createdAt,
        Long orgId,
        Long imageId,
        @Nullable String ipAddress,
        String sshUsername,
        String sshHost,
        @Nullable ResourceRole myResourceRole,
        @Nullable LocalDate startDate,
        @Nullable LocalDate endDate,
        @Nullable Instant expiryStoppedAt,
        @Nullable ProvisioningTaskResponse provisioning,
        @Nullable VmDeletionResponse deletion,
        List<PublicationView> publications,
        boolean passwordAvailable,
        boolean passwordRevealAllowed,
        Instant updatedAt) {

    public static VmDetailResponse from(Vm vm, String groupName, String orgName, String displayName,
            String ipAddress, String sshHost, ResourceRole myResourceRole,
            boolean passwordRevealAllowed, ProvisioningTaskResponse provisioning,
            List<PublicationView> publications) {
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName, orgName,
                displayName, vm.getRequestId(), vm.getStatusDetail(), vm.isSshGatewayBlocked(),
                vm.getCreatedAt(), vm.getOrgId(),
                vm.getImageId(), ipAddress, vm.getSshUsername(), sshHost, myResourceRole,
                vm.getStartDate(), vm.getEndDate(), vm.getExpiryStoppedAt(), provisioning,
                VmDeletionResponse.from(vm), publications,
                vm.getPasswordEnc() != null, passwordRevealAllowed, vm.getUpdatedAt());
    }
}
