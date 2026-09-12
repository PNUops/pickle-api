package kr.ac.pusan.pickle.gpu.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.gpu.GpuReviewDecision;

public record DecideGpuReviewRequest(@NotNull(message = "필수 값을 입력해 주세요.") GpuReviewDecision decision, @NotBlank(message = "사유를 입력해 주세요.") @Size(max = 2000, message = "사유는 2000자 이하여야 합니다.") String reason) {}
