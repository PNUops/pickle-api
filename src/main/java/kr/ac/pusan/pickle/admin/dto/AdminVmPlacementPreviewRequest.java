package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

/** Proposed values shared by a saved request and a submit-and-approve form. */
public record AdminVmPlacementPreviewRequest(
        @NotNull(message = "기관을 지정해 주세요.") UUID orgId,
        @NotNull(message = "OS 이미지를 지정해 주세요.") UUID imageId,
        @NotNull(message = "vCPU를 입력해 주세요.") @Min(value = 1, message = "vCPU는 1 이상으로 입력해 주세요.") Integer vcpu,
        @NotNull(message = "메모리를 입력해 주세요.") @Min(value = 256, message = "메모리는 256MiB 이상으로 입력해 주세요.") Integer memoryMb,
        @NotNull(message = "디스크를 입력해 주세요.") @Min(value = 1, message = "디스크는 1GiB 이상으로 입력해 주세요.") Integer diskGb,
        @Schema(description = "명령 대상 워크스페이스이며 지정 범위 권한으로 조회할 때 필요해요.")
        @Nullable UUID workspaceId,
        @Schema(description = "같은 사양으로 만들 VM 수예요. 생략하면 1대이며 용량을 예약하지 않아요.")
        @Min(value = 1, message = "VM 수는 1 이상으로 입력해 주세요.")
        @Max(value = 200, message = "VM 수는 200 이하로 입력해 주세요.") @Nullable Integer count,
        @Schema(description = "직접 지정할 노드예요. 생략하면 자동 배치 조건으로 계산해요.")
        @Nullable UUID nodeId) {
    public AdminVmPlacementPreviewRequest(UUID orgId, UUID imageId, Integer vcpu, Integer memoryMb, Integer diskGb) {
        this(orgId, imageId, vcpu, memoryMb, diskGb, null, null, null);
    }
}
