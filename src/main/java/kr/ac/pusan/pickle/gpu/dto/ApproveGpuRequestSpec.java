package kr.ac.pusan.pickle.gpu.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import kr.ac.pusan.pickle.gpu.StrictGpuHoursDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

public record ApproveGpuRequestSpec(
        @NotNull(message = "승인할 GPU 임대 시간을 입력해 주세요.") @Min(value = 1, message = "임대 시간은 1시간 이상이어야 합니다.") @Schema(description = "관리자가 승인하는 양의 정수 시간입니다. 1.5처럼 정수가 아닌 값이나 문자열은 허용하지 않습니다.") @JsonDeserialize(using = StrictGpuHoursDeserializer.class) Integer leaseHours,
        @Nullable UUID gpuId, @Nullable Integer priority, @Nullable @Size(max = 2000, message = "사유는 2000자 이하여야 합니다.") String priorityReason) {}
