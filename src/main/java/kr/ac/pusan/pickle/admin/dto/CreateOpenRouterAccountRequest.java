package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CreateOpenRouterAccountRequest(
        @Schema(description = "Account를 소유할 기관 공개 ID")
        @NotNull UUID orgId,
        @Schema(description = "기관 관리자가 구분하는 사업 account 이름")
        @NotBlank @Size(max = 120) String name,
        @Schema(description = "재원 참조. 없으면 null")
        @Size(max = 500) @Nullable String fundingReference,
        @Schema(description = "증빙 참조. 없으면 null")
        @Size(max = 500) @Nullable String evidenceReference,
        @Schema(description = "오입력 방지를 위해 name과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
