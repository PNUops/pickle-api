package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Three cuts of the same window at three grains: what was called, how it came
 * in, and which capabilities are being used at all.
 *
 * <p>They travel together because they are read together. Splitting them across
 * cards or routes would make a reader add the same window up twice to compare
 * two of them.
 */
public record LlmUsageBreakdownResponse(
        @Schema(description = "이 기간에 실제로 호출된 모델만, 요청이 많은 순")
        List<LlmUsageModelBreakdownResponse> models,
        @Schema(description = "이 기간에 실제로 들어온 경로만, 요청이 많은 순")
        List<InstitutionAdminLlmEndpointUsageResponse> endpointKinds,
        @Schema(description = "기능 권한마다 부여 현황과 사용 현황을 한 행으로 제공해요.")
        List<LlmUsagePassthroughGrantResponse> passthroughGrants) {
}
