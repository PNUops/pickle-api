package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Registry identity plus the latest gateway observation for one upstream. */
@Schema(description = "upstream 등록 정보와 gateway의 최신 현재 상태. ORG 응답은 허용 범위의 "
        + "upstream만 포함하며 내부 ref·소유 기관 ID를 숨깁니다.")
public record LlmUpstreamStatusResponse(
        @Schema(description = "upstream 등록 공개 ID. gateway에만 있고 등록되지 않은 ref면 null")
        @Nullable UUID id,
        @Schema(description = "gateway 내부 ref. SYS 계층에만 반환하며 ORG에서는 null")
        @Nullable String ref,
        String name,
        @Schema(description = "등록된 upstream 종류. 미등록 ref면 null")
        @Nullable LlmUpstreamKind kind,
        @Schema(description = "소유 기관 공개 ID. SYS 계층의 기관 소유 upstream에만 있고 shared·미등록·ORG에서는 null")
        @Nullable UUID orgId,
        @Schema(description = "기관 전용 여부. 미등록 ref면 null")
        @Nullable Boolean dedicated,
        @Schema(description = "등록부의 운영 활성 여부. 미등록 ref면 null")
        @Nullable Boolean enabled,
        @Schema(description = "format 1 최신 gateway 목록에 포함됐는지. 구 gateway 보고에는 기존 값을 보존")
        boolean configured,
        @Schema(description = "등록부와 versioned gateway 보고의 관계 및 신선도")
        LlmUpstreamReportState reportState,
        @Schema(description = "신선한 active·passive·catalog 관측을 합성한 현재 가용성")
        LlmUpstreamAvailability availability,
        @Schema(description = "이 upstream 관측 행을 마지막으로 받은 시각. 아직 보고되지 않았으면 null")
        @Nullable Instant lastReportedAt,
        LlmPassiveObservationResponse passive,
        LlmActiveObservationResponse active,
        LlmCatalogObservationResponse catalog) {
}
