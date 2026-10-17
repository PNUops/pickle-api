package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;

/** Explicit acknowledgement of the exact closing snapshot. */
public record TerminationConfirmation(
        @NotBlank @Schema(description = "현재 종료 영향 조회에서 받은 지문", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1) String fingerprint,
        @AssertTrue @Schema(description = "정확한 대상과 종료 영향을 확인했을 때만 true", requiredMode = Schema.RequiredMode.REQUIRED) boolean confirmed) { }
