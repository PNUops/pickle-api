package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contract schema {@code RegisterOpenRouterCredentialRequest} — the first
 * credential on an account that has none. Field-identical to
 * {@link StageOpenRouterCredentialRequest}, which is the rotation candidate;
 * the two stay separate because the generated contract names schemas after
 * them, and a register endpoint carrying a "Stage..." schema is a public name
 * that has to be renamed later. Keep the constraints on both in step.
 */
public record RegisterOpenRouterCredentialRequest(
        @Schema(writeOnly = true, description = "OpenRouter management key. 응답에는 반환되지 않습니다.")
        @NotBlank @Size(max = 4096) String managementKey,
        @Schema(description = "Account 이름과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
