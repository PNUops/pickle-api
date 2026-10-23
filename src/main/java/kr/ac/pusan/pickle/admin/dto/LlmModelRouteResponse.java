package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Configured model relations projected through the current status response's upstreams. */
@Schema(description = "공개·활성 TOKEN 모델의 주·대체 공급원 구성 관계. 현재 응답에서 읽을 수 있는 "
        + "공급원 연결이 하나 이상인 모델만 포함하며 전체 모델 목록이나 실제 처리 경로가 아닙니다.")
public record LlmModelRouteResponse(
        @Schema(description = "요청의 model 필드에 사용하는 공개 모델명")
        String publicName,
        @Schema(description = "주 공급원 구성의 공개 참조")
        LlmModelRouteReference primary,
        @Schema(description = "대체 공급원 구성의 공개 참조")
        LlmModelRouteReference fallback) {

    @Schema(name = "LlmModelRouteReference", description = "같은 상태 응답에 포함된 등록 공급원의 공개 참조. "
            + "VISIBLE일 때만 ID·이름을 반환하고 나머지 상태에서는 둘 다 null입니다.")
    public record LlmModelRouteReference(
            LlmModelRouteState state,
            @Schema(description = "현재 응답에서 읽을 수 있는 등록 공급원 공개 ID")
            @Nullable UUID upstreamId,
            @Schema(description = "현재 응답에서 읽을 수 있는 등록 공급원 표시 이름")
            @Nullable String upstreamName) {
    }

    @Schema(name = "LlmModelRouteState", description = "VISIBLE은 현재 응답에서 확인할 수 있는 등록 공급원입니다. "
            + "NOT_CONFIGURED는 SYS 응답에서만 구성이 없음을 뜻합니다. NOT_PROVIDED는 참조를 제공하지 "
            + "않는 상태이며 ORG에서는 미설정·범위 밖·미등록을 구별하지 않습니다.")
    public enum LlmModelRouteState {
        VISIBLE,
        NOT_CONFIGURED,
        NOT_PROVIDED
    }
}
