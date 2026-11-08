package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Observation made by real user requests; it never comes from a probe. */
@Schema(description = "실제 사용자 요청에서 얻은 passive 관측. lastAttemptAt은 표시용이며, "
        + "가용성은 최근 15분 안의 lastSuccessAt과 lastFailureAt으로 판정해요.")
public record LlmPassiveObservationResponse(
        @Schema(description = "마지막 실제 요청 시각. key-local 거절이나 취소도 포함하므로 상태 판정에는 사용하지 않음")
        @Nullable Instant lastAttemptAt,
        @Schema(description = "마지막 upstream 성공 시각. 15분이 지나면 현재 health 근거로 사용하지 않음")
        @Nullable Instant lastSuccessAt,
        @Schema(description = "마지막 health-impacting upstream 실패 시각. 15분이 지나면 현재 health 근거로 사용하지 않음")
        @Nullable Instant lastFailureAt,
        @Schema(description = "마지막 health-impacting 실패 분류. 실패 이력이 없으면 null")
        @Nullable String lastFailureType,
        @Schema(description = "연속 health-impacting 실패 수. 보고되지 않았으면 null")
        @Nullable Integer consecutiveFailures,
        @Schema(description = "실제 요청 실패로 생긴 routing cooldown 종료 시각. cooldown이 없으면 null")
        @Nullable Instant cooldownUntil) {
}
