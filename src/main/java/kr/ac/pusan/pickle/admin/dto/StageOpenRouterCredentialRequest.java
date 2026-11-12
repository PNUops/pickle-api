package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StageOpenRouterCredentialRequest(
        @Schema(writeOnly = true, description = "OpenRouter 관리용 키이며 응답에 반환하지 않아요.")
        @NotBlank @Size(max = 4096) String managementKey,
        @Schema(description = "사업 계정 이름과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
