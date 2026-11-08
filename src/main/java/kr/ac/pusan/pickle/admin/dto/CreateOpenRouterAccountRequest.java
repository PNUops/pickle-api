package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import org.jspecify.annotations.Nullable;

public record CreateOpenRouterAccountRequest(
        @Schema(description = "사업 계정을 소유할 기관의 공개 ID")
        @NotNull UUID orgId,
        @Schema(description = "기관 관리자가 구분하는 사업 계정 이름")
        @NotBlank @Size(max = 120) String name,
        @Schema(description = "이 사업 계정의 비용을 청구하는 사업. 없으면 null")
        @Size(max = 500) @Nullable String program,
        @Schema(description = "이 사업 계정의 담당자. 없으면 null")
        @Size(max = 500) @Nullable String contact,
        @Schema(description = "승인 화면에 처음 채울 유료 모델 허용 목록의 기본값이에요. 비우면 제한 없는 목록을 "
                + "기본값으로 써요."
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        @Size(max = 50, message = "허용할 모델은 50개 이내로 지정해 주세요.")
        @Nullable List<String> defaultCreditAllowedModels,
        @Schema(description = "승인 화면에 처음 채울 유료 모델 차단 목록의 기본값이에요. 비우면 차단 없는 목록을 "
                + "기본값으로 써요."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        @Size(max = 50, message = "차단할 모델은 50개 이내로 지정해 주세요.")
        @Nullable List<String> defaultCreditDeniedModels,
        @Schema(description = "오입력 방지를 위해 name과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
