package kr.ac.pusan.pickle.request.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.workspace.dto.TerminationConfirmation;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record CancelRequestRecipientRequest(
        @Schema(description = "해당 대상자의 생성 대기를 취소하는 사유", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
        @NotBlank(message = "취소 사유를 입력해 주세요.") @Size(min = 1, max = 500, message = "취소 사유는 500자 이하여야 합니다.") String reason,
        @Valid @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TerminationConfirmation confirmation) { }
