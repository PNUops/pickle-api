package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record ConfirmOpenRouterAccountRequest(
        @Schema(description = "Account 이름과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
