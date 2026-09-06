package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import kr.ac.pusan.pickle.llm.dto.LlmEndpointKindUsageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
import kr.ac.pusan.pickle.llm.dto.LlmServedModelUsageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmUsageCostPointResponse;

/**
 * Contract schema {@code AdminLlmKeyUsage}: one key's usage as an administrator
 * sees it, which is the owner's view plus three breakdowns the owner has no use
 * for.
 *
 * <p>{@code trend} is the owner's response unchanged, and that is deliberate:
 * an administrator answering "the student says 400, the screen says 398" is a
 * question nobody wants, and it cannot arise while both screens read the same
 * numbers out of the same query.
 *
 * <p>There is no vendor-meter block here. The meter already travels inside
 * {@code trend.budget}, and a second copy beside it would put one fact in two
 * places on the same screen.
 */
public record AdminLlmKeyUsageResponse(
        @Schema(description = "키 소유자가 보는 것과 같은 응답, 같은 계산")
        LlmKeyUsageTrendResponse trend,
        @Schema(description = """
                일별 금액. 소유자 화면에는 없는 계열입니다 — 소유자는 모델별 금액만 봅니다.""")
        List<LlmUsageCostPointResponse> costPoints,
        @Schema(description = "이 기간에 실제로 호출된 경로만, 요청이 많은 순")
        List<LlmEndpointKindUsageResponse> endpointKinds,
        @Schema(description = """
                공급자가 요청과 다른 모델로 응답한 사례. 없으면 빈 목록이고, 빈 것이 \
                「대체가 없었다」를 증명하지는 않습니다.""")
        List<LlmServedModelUsageResponse> servedModels) {
}
