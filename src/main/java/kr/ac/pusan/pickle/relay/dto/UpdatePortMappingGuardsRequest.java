package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Contract op {@code updateAdminPortMappingGuards} body (documentation shape —
 * the handler binds the raw body so an omitted field is distinguishable from
 * an explicit {@code null}). Per field: omitted = keep the current value,
 * {@code null} = clear to the agent default, {@code 0} = disable the guard,
 * {@code >0} = explicit limit.
 */
public record UpdatePortMappingGuardsRequest(
        @Nullable
        @Schema(description = "매핑별 동시 연결 상한 (null = 기본값, 0 = 해제)")
        Integer ctMax,
        @Nullable
        @Schema(description = "초당 신규 연결 상한")
        Integer newConnRate,
        @Nullable
        @Schema(description = "신규 연결 버스트")
        Integer newConnBurst,
        @Nullable
        @Schema(description = "출발지별 초당 신규 연결 상한")
        Integer perSourceRate,
        @Nullable
        @Schema(description = "출발지별 버스트")
        Integer perSourceBurst) {
}
