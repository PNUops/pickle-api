package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
        InstitutionAdminLlmTrendResponse trend,
        @Schema(description = """
                일별 금액 계열이에요.""")
        @org.jspecify.annotations.Nullable List<LlmUsageCostPointResponse> costPoints,
        @Schema(description = "이 기간에 실제로 호출된 경로만, 요청이 많은 순")
        @org.jspecify.annotations.Nullable List<InstitutionAdminLlmEndpointUsageResponse> endpointKinds,
        @Schema(description = """
                공급자가 전달받은 모델과 다른 모델로 응답한 기록이에요. 빈 목록만으로 대체가 없었다고 판단할 수는 없어요.""")
        @org.jspecify.annotations.Nullable List<LlmServedModelUsageResponse> servedModels, boolean financeVisible) {
}
