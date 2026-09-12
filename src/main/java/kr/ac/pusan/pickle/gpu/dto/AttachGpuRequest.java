package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record AttachGpuRequest(@NotNull(message = "필수 값을 입력해 주세요.") UUID vmId, @NotNull(message = "필수 값을 입력해 주세요.") @AssertTrue(message = "가상머신 중단에 동의해 주세요.") Boolean confirmed) {}
