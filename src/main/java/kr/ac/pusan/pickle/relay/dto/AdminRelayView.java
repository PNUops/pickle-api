package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminRelayView} — relay observability row. Heartbeat
 * fields ({@code appliedGeneration}, {@code agentVersion}, {@code lastError})
 * are claims reported by the relay agent, not measurements.
 */
public record AdminRelayView(
        Long id,
        String name,
        @Nullable
        @Schema(description = "사용자 접속용 공개 호스트 (설정 전이면 null)")
        String publicHost,
        @Schema(description = "공개 포트 대역 시작")
        int bandStart,
        @Schema(description = "공개 포트 대역 끝")
        int bandEnd,
        boolean enabled,
        @Schema(description = "동기화 토큰 발급 여부 (미발급이면 에이전트 인증이 항상 실패)")
        boolean tokenIssued,
        @Schema(description = "현재 매핑 세대 (매핑 변경마다 증가)")
        long mappingGeneration,
        @Schema(description = "에이전트가 적용을 확인한 마지막 세대")
        long appliedGeneration,
        @Nullable Instant lastContactAt,
        @Schema(description = "접촉 두절 여부 (마지막 동기화가 폴링 주기 3배를 초과)")
        boolean contactLost,
        @Nullable String agentVersion,
        @Nullable
        @Schema(description = "에이전트가 보고한 마지막 적용 오류(JSON, 정화됨). 없으면 null")
        String lastError,
        @Schema(description = "이 릴레이의 매핑 수")
        long mappingCount,
        @Schema(description = "공개 포트 대역 사용률(%)")
        int bandUsagePercent) {
}
