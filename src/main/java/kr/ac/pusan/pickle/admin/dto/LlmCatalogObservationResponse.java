package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Latest configured-vs-served catalogue comparison from the gateway. */
@Schema(description = "설정 catalog와 성공한 /models 응답의 비교. 오래된 비교 결과는 "
        + "availability를 낮추지 않습니다.")
public record LlmCatalogObservationResponse(
        @Schema(description = "catalog 비교 상태. expected set이 없는 passthrough upstream은 NOT_APPLICABLE")
        LlmCatalogStatus status,
        @Schema(description = "비교한 기대 모델 수. 비교하지 않았으면 null")
        @Nullable Integer expectedModelCount,
        @Schema(description = "기대했지만 /models 응답에 없던 모델 수. MATCH/MISMATCH에서 없으면 0, 비교하지 않았으면 null")
        @Nullable Integer missingModelCount,
        @Schema(description = "/models 응답에만 있던 예상 밖 모델 수. curated non-passthrough 비교에서 없으면 0, "
                + "passthrough subset 비교·미비교 상태에서는 null")
        @Nullable Integer unexpectedModelCount,
        @Schema(description = "누락된 public model 이름 표본(최대 20개). SYS 계층에만 반환하며 ORG에서는 항상 빈 배열")
        List<String> missingPublicModels) {
}
