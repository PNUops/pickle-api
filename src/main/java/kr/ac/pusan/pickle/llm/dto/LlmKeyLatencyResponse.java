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
        @Schema(description = "백분위를 낸 요청 수. 표본이 적을수록 p99는 흔들립니다.")
        long samples) {
}
