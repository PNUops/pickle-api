package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Confirmation that vendor-console revocation happened before local erasure. */
public record FinalizeOpenRouterCredentialRequest(
        @Schema(description = "Account 이름과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName,
        @NotNull
        @AssertTrue(message = "Vendor console에서 management key를 폐기했음을 확인해 주세요.")
        @Schema(description = "Vendor console에서 대상 management key를 폐기했으면 true. API는 vendor key를 폐기하지 않습니다.")
        Boolean vendorRevocationConfirmed) {
}
