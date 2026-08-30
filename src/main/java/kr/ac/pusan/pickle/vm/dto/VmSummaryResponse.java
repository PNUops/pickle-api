package kr.ac.pusan.pickle.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmSummary}. {@code workspaceName} is joined for the list
 * view; {@code orgName} and {@code displayName} (VM setting, null when unset)
 * are joined for the console list/admin surfaces.
 *
 * <p>A member of the owning workspace who holds no grant on a VM still sees that it
 * exists, and this row is then <b>restricted</b>: {@code accessLimited} is true,
 * {@code ownerNames} says who to ask, and everything else the row would reveal
 * about the machine is omitted rather than blanked in the console. The redaction
 * happens here because a field the API sends has already left the building.
 * {@code name} is the SSH slug on an open row, so a restricted row carries the
 * display name there instead — see {@link #restricted}.
 */
public record VmSummaryResponse(
        UUID id,
        @Schema(description = "SSH 슬러그. 접근 권한이 없으면 대신 표시 이름이 들어갑니다.")
        String name,
        @Schema(description = "SSH 슬러그. 접근 권한이 없으면 생략됩니다.")
        @Nullable String hostname,
        VmStatus status,
        @Schema(description = "vCPU. 접근 권한이 없으면 생략됩니다.")
        @Nullable Integer vcpu,
        @Schema(description = "메모리(MiB). 접근 권한이 없으면 생략됩니다.")
        @Nullable Integer memoryMb,
        @Schema(description = "디스크(GiB). 접근 권한이 없으면 생략됩니다.")
        @Nullable Integer diskGb,
        @Schema(description = "소유 워크스페이스. 행이 사라진 경우에만 null입니다.")
        @Nullable UUID workspaceId,
        String workspaceName,
        @Schema(description = "소유 기관. 접근 권한이 없거나 기관 행이 사라진 경우 null입니다.")
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Nullable String displayName,
        @Nullable UUID requestId,
        @Nullable String statusDetail,
        @Nullable Boolean sshGatewayBlocked,
        @Nullable LocalDate endDate,
        @Nullable Instant expiryStoppedAt,
        @Schema(description = "예약된 삭제. 예약이 없거나 접근 제한 행이면 null입니다.")
        @Nullable VmDeletionResponse deletion,
        Instant createdAt,
        @Schema(description = "true면 이 VM의 접근 권한이 없어 이름·상태·소유자만 표시됩니다.")
        boolean accessLimited,
        @Schema(description = "이 VM의 소유자 이름. 접근을 요청할 상대입니다.")
        List<String> ownerNames,
        @Schema(description = "접근 권한이 없어도 접근 권한 목록을 관리할 수 있는지. 워크스페이스 소유자가 참입니다.")
        boolean accessManageAllowed) {

    public static VmSummaryResponse from(Vm vm, UUID workspaceId, String workspaceName,
            @Nullable UUID orgId, String orgName, String displayName, UUID requestId,
            @Nullable UUID deleteRequestedById) {
        return new VmSummaryResponse(vm.getPublicId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), workspaceId, workspaceName, orgId, orgName,
                displayName, requestId, vm.getStatusDetail(), vm.isSshGatewayBlocked(),
                vm.getEndDate(), vm.getExpiryStoppedAt(), VmDeletionResponse.from(vm, deleteRequestedById),
                vm.getCreatedAt(), false, List.of(), false);
    }

    /**
     * Name, state and who to ask — nothing about the machine itself.
     *
     * <p>{@code name} is the SSH slug: the same string one types to reach the
     * machine, so it is exactly what somebody without a grant must not be handed.
     * The display name takes its place, and {@code displayName} itself is left
     * null so the row still renders as one label — a list that shows both would
     * print the name twice.
     */
    public static VmSummaryResponse restricted(Vm vm, UUID workspaceId, String workspaceName,
            String displayName, List<String> ownerNames, boolean accessManageAllowed) {
        return new VmSummaryResponse(vm.getPublicId(), restrictedName(vm, displayName), null, vm.getStatus(),
                null, null, null, workspaceId, workspaceName, null, null,
                null, null, null, null,
                null, null, null, vm.getCreatedAt(), true, ownerNames, accessManageAllowed);
    }

    /**
     * What a restricted row is called. The display name when the VM has one;
     * otherwise its id, which the row already carries and which no slug can be
     * confused with. Never null: the console picks the label as
     * {@code displayName || name}, so a null name would leave the row nameless.
     */
    private static String restrictedName(Vm vm, String displayName) {
        return displayName != null && !displayName.isBlank() ? displayName : "VM #" + vm.getPublicId();
    }
}
