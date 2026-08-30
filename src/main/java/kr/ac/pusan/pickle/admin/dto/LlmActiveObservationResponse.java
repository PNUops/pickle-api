package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Latest out-of-band models probe. It does not alter routing or cooldown. */
@Schema(description = "gateway의 out-of-band /models probe 최신 결과. routing과 cooldown을 "
        + "바꾸지 않으며 마지막 시도가 intervalSeconds의 3배보다 오래되면 availability에는 UNKNOWN으로 반영됩니다.")
public record LlmActiveObservationResponse(
        @Schema(description = "마지막 probe 시도 시각. 아직 시도하지 않았으면 null")
        @Nullable Instant lastAttemptAt,
        @Schema(description = "마지막 probe 성공 시각. 성공 이력이 없으면 null")
        @Nullable Instant lastSuccessAt,
        @Schema(description = "마지막 probe 실패 시각. 실패 이력이 없으면 null")
        @Nullable Instant lastFailureAt,
        @Schema(description = "마지막으로 보고된 raw probe 결과. freshness가 만료돼도 값 자체는 OK로 남을 수 "
                + "있으며, 현재 상태는 lastAttemptAt·intervalSeconds를 직접 재해석하지 말고 top-level availability를 사용. "
                + "AUTH_UNVERIFIED는 도달했지만 인증 여부를 확인하지 못한 상태")
        LlmActiveProbeStatus status,
        @Schema(description = "최신 probe 실패 분류. 실패가 아니면 null")
        @Nullable String failureType,
        @Schema(description = "이 upstream의 probe 주기(초). 마지막 시도 freshness 판정의 기준")
        @Nullable Integer intervalSeconds,
        @Schema(description = "마지막 probe 시도가 intervalSeconds의 3배보다 오래됐는지 서버가 계산한 값. "
                + "미관측 UNKNOWN은 false")
        boolean stale,
        @Schema(description = "최신 probe 응답 시간(ms). 측정값이 없으면 null")
        @Nullable Long latencyMs,
        @Schema(description = "성공한 /models 응답의 모델 수. 성공했고 빈 목록이면 0, 성공 이력이 없으면 null")
        @Nullable Integer modelCount,
        @Schema(description = "연속 probe 실패 수. 보고되지 않았으면 null")
        @Nullable Integer consecutiveFailures) {
}
