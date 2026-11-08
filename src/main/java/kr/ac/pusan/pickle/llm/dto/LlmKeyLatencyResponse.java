package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contract schema {@code LlmKeyLatency}: how long the key's successful
 * requests took, over the requested window.
 *
 * <p>Successful ones only. A timeout's duration is the timeout setting and a
 * refusal's is nearly zero, so mixing them in would move the percentiles
 * without telling anyone anything about how the service performs.
 */
public record LlmKeyLatencyResponse(
        @Schema(description = "정상 응답 요청의 중앙값 응답 시간(ms).")
        long p50Ms,
        long p90Ms,
        long p99Ms,
        @Schema(description = "백분위를 계산한 요청 수예요. 표본이 적으면 p99의 변동이 커요.")
        long samples) {
}
