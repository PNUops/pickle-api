package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Read-only current state of the LLM gateway and its upstreams. */
public record LlmStatusResponse(
        Instant observedAt,
        LlmGatewayStatusResponse gateway,
        List<LlmUpstreamStatusResponse> upstreams,
        @Schema(description = "공개·활성 TOKEN 모델 중 현재 응답의 읽을 수 있는 공급원과 연결된 구성 관계. "
                + "전체 모델 목록이 아니며 빈 배열은 현재 범위에서 연결을 확인할 수 없음을 뜻합니다. "
                + "실제 처리·사용량·영향 키를 나타내지 않습니다.")
        List<LlmModelRouteResponse> modelRoutes) {
}
