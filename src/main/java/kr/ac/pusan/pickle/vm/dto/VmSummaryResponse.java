package kr.ac.pusan.pickle.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmSummary}. {@code groupName} is joined for the list
 * view; {@code orgName} and {@code displayName} (VM setting, null when unset)
 * are joined for the console list/admin surfaces.
 *
 * <p>A member of the owning group who holds no grant on a VM still sees that it
 * exists, and this row is then <b>restricted</b>: {@code accessLimited} is true,
 * {@code ownerNames} says who to ask, and everything else the row would reveal
 * about the machine is omitted rather than blanked in the console. The redaction
 * happens here because a field the API sends has already left the building.
 */
public record VmSummaryResponse(
        Long id,
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
        Long groupId,
        String groupName,
        @Nullable String orgName,
        @Nullable String displayName,
        @Nullable Long requestId,
        @Nullable String statusDetail,
        @Nullable Boolean sshGatewayBlocked,
        @Nullable LocalDate endDate,
        @Nullable Instant expiryStoppedAt,
        Instant createdAt,
        @Schema(description = "true면 이 VM의 접근 권한이 없어 이름·상태·소유자만 표시됩니다.")
        boolean accessLimited,
        @Schema(description = "이 VM의 소유자 이름. 접근을 요청할 상대입니다.")
        List<String> ownerNames,
        @Schema(description = "접근 권한이 없어도 접근 권한 목록을 관리할 수 있는지. 그룹 소유자가 참입니다.")
        boolean accessManageAllowed) {

    public static VmSummaryResponse from(Vm vm, String groupName, String orgName,
            String displayName) {
        return new VmSummaryResponse(vm.getId(), vm.getName(), vm.getHostname(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getGroupId(), groupName, orgName,
                displayName, vm.getRequestId(), vm.getStatusDetail(), vm.isSshGatewayBlocked(),
                vm.getEndDate(), vm.getExpiryStoppedAt(), vm.getCreatedAt(), false, List.of(),
                false);
    }

    /** Name, state and who to ask — nothing about the machine itself. */
    public static VmSummaryResponse restricted(Vm vm, String groupName, String displayName,
            List<String> ownerNames, boolean accessManageAllowed) {
        return new VmSummaryResponse(vm.getId(), vm.getName(), null, vm.getStatus(),
                null, null, null, vm.getGroupId(), groupName, null,
                displayName, null, null, null,
                null, null, vm.getCreatedAt(), true, ownerNames, accessManageAllowed);
    }
}
