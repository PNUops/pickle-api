package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Confirmation that vendor-console revocation happened before local erasure. */
public record FinalizeOpenRouterCredentialRequest(
        @Schema(description = "사업 계정 이름과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName,
        @NotNull
        @AssertTrue(message = "Vendor console에서 management key를 폐기했음을 확인해 주세요.")
        @Schema(description = "OpenRouter에서 대상 관리용 키를 폐기했음을 운영자가 확인하면 true로 보내요. API가 관리용 키 자체를 폐기하지는 않아요.")
        Boolean vendorRevocationConfirmed) {
}
