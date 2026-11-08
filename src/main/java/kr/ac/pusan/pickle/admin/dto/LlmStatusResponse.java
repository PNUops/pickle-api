package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Read-only current state of the LLM gateway and its upstreams. */
public record LlmStatusResponse(
        Instant observedAt,
        LlmGatewayStatusResponse gateway,
        List<LlmUpstreamStatusResponse> upstreams,
        @Schema(description = "공개 상태의 활성 TOKEN 모델 중 현재 응답에서 읽을 수 있는 공급원과 연결된 구성 관계예요. "
                + "전체 모델 목록은 아니며 빈 배열은 현재 범위에서 연결을 확인할 수 없다는 뜻이에요. "
                + "실제 처리나 사용량, 영향받은 키를 나타내지 않아요.")
        List<LlmModelRouteResponse> modelRoutes) {
}
