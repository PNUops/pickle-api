package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Proposed values shared by a saved request and a submit-and-approve form. */
public record AdminVmPlacementPreviewRequest(
        @NotNull(message = "기관을 지정해 주세요.") UUID orgId,
        @NotNull(message = "OS 이미지를 지정해 주세요.") UUID imageId,
        @NotNull(message = "vCPU를 입력해 주세요.") @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.") Integer vcpu,
        @NotNull(message = "메모리를 입력해 주세요.") @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.") Integer memoryMb,
        @NotNull(message = "디스크를 입력해 주세요.") @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.") Integer diskGb) { }
