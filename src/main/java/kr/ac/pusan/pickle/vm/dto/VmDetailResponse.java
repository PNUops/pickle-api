package kr.ac.pusan.pickle.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * surface: the requester's workspace role, whether the stored password is available
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
        Long workspaceId,
        String workspaceName,
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
        @Schema(description = "SSH·웹 터미널로 이 VM에 접속할 수 있는지")
        boolean accessAllowed,
        @Schema(description = "전원을 제어할 수 있는지")
        boolean powerControlAllowed,
        @Schema(description = "VM 설정을 볼·바꿀 수 있는지")
        boolean settingsEditAllowed,
        @Schema(description = "접근 권한 목록을 관리할 수 있는지")
        boolean accessManageAllowed,
        @Schema(description = "삭제를 접수할 수 있는지")
        boolean deleteAllowed,
        Instant updatedAt) {

    /**
     * The five booleans are the console's gate. They exist because the answer is
     * no longer a comparison the client can make: it depends on the access list
     * and on a workspace owner's standing rights, which the client cannot see.
     * Admin surfaces pass {@code myResourceRole} null and get them all false —
     * their own authorization is org-scoped and lives elsewhere.
     */
    public static VmDetailResponse from(Vm vm, String workspaceName, String orgName, String displayName,
            String ipAddress, String sshHost, ResourceRole myResourceRole,
            boolean passwordRevealAllowed, boolean accessManageAllowed,
            ProvisioningTaskResponse provisioning, List<PublicationView> publications) {
        boolean atLeastMember = myResourceRole != null
                && myResourceRole.atLeast(ResourceRole.MEMBER);
        boolean atLeastEditor = myResourceRole != null
                && myResourceRole.atLeast(ResourceRole.EDITOR);
        return new VmDetailResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getWorkspaceId(), workspaceName, orgName,
                displayName, vm.getRequestId(), vm.getStatusDetail(), vm.isSshGatewayBlocked(),
                vm.getCreatedAt(), vm.getOrgId(),
                vm.getImageId(), ipAddress, vm.getSshUsername(), sshHost, myResourceRole,
                vm.getStartDate(), vm.getEndDate(), vm.getExpiryStoppedAt(), provisioning,
                VmDeletionResponse.from(vm), publications,
                vm.getPasswordEnc() != null, passwordRevealAllowed,
                atLeastMember, atLeastMember, atLeastEditor, accessManageAllowed,
                accessManageAllowed, vm.getUpdatedAt());
    }
}
