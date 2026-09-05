package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
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
        @Schema(description = "승인 화면 프리필에 쓸 유료 모델 허용 목록 기본값. 비우면 제한 없음이 "
                + "기본이 됩니다.")
        @Size(max = 50, message = "모델은 최대 50개까지 허용할 수 있습니다.")
        @Nullable List<String> defaultCreditAllowedModels,
        @Schema(description = "승인 화면 프리필에 쓸 유료 모델 차단 목록 기본값. 비우면 차단 "
                + "없음이 기본이 됩니다.")
        @Size(max = 50, message = "모델은 최대 50개까지 차단할 수 있습니다.")
        @Nullable List<String> defaultCreditDeniedModels,
        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS}),
                arraySchema = @Schema(description = "승인 화면 프리필에 쓸 기능 권한 기본값. "
                        + "비우면 아무 기능도 부여되지 않은 상태가 기본이 됩니다."))
        @Size(max = 20, message = "기능은 최대 20개까지 허용할 수 있습니다.")
        @Nullable List<String> defaultPassthroughEndpoints,
        @Schema(description = "오입력 방지를 위해 name과 정확히 같아야 하는 확인값")
        @NotBlank @Size(max = 120) String confirmName) {
}
