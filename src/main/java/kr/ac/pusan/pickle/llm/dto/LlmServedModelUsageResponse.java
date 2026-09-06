package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contract schema {@code LlmServedModelUsage}: how often the upstream answered
 * with a model other than the one it was asked for, and which one.
 *
 * <p>A row exists only for a fallback. The gateway writes the served name only
 * when it differs from the model it sent, because it is the only side holding
 * both upstream names; the public name in the event is ours and is deliberately
 * not the upstream one, so comparing against it here would be wrong in both
 * directions.
 *
 * <p>It is evidence, not a guarantee. The rewrite that hides the upstream name
 * from the caller is top-level only, and a router has been measured returning
 * its real choice deeper in the body while the top-level field still read the
 * router's own name. An empty list does not prove nothing was substituted.
 */
public record LlmServedModelUsageResponse(
        @Schema(description = "공급자가 실제로 응답한 모델 이름. 요청한 모델과 다를 때만 기록됩니다.")
        String servedModelName,
        @Schema(description = "그 이름으로 돌아온 요청 수")
        long requests) {
}
