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
        @Schema(description = "기관 관리자가 구분하는 사업 계정 이름")
        @NotBlank @Size(max = 120) String name,
        @Schema(description = "이 account가 청구되는 사업. 없으면 null")
        @Size(max = 500) @Nullable String program,
        @Schema(description = "이 account를 물어볼 담당자. 없으면 null")
        @Size(max = 500) @Nullable String contact,
        @Schema(description = "오입력 방지를 위해 name과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
