package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.relay.PortForwardApplyState;
import kr.ac.pusan.pickle.relay.PortMappingProto;
import kr.ac.pusan.pickle.relay.PortMappingStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminPortMappingResponse} — one mapping with context. */
public record AdminPortMappingResponse(
        UUID id,
        UUID relayId,
        String relayName,
        UUID vmId,
        @Nullable String vmName,
        PortMappingProto proto,
        int publicPort,
        int targetPort,
        PortMappingStatus status,
        @Nullable
        @Schema(description = "정지 사유 (SUSPENDED일 때)")
        String suspendedReason,
        @Nullable
        @Schema(description = "정지한 관리자 id (자동 정지면 null)")
        UUID suspendedBy,
        @Nullable
        @Schema(description = "정지한 관리자 이름 (자동 정지면 null)")
        String suspendedByName,
        PortForwardApplyState applyState,
        @Nullable
        @Schema(description = "동시 연결 상한 오버라이드 (null = 에이전트 기본, 0 = 해제)")
        Integer ctMax,
        @Nullable
        @Schema(description = "초당 신규 연결 상한 오버라이드")
        Integer newConnRate,
        @Nullable
        @Schema(description = "신규 연결 버스트 오버라이드")
        Integer newConnBurst,
        @Nullable
        @Schema(description = "출발지별 초당 신규 연결 상한 오버라이드")
        Integer perSourceRate,
        @Nullable
        @Schema(description = "출발지별 버스트 오버라이드")
        Integer perSourceBurst,
        @Nullable UUID createdBy,
        @Nullable
        @Schema(description = "매핑을 만든 관리자 이름")
        String createdByName,
        Instant createdAt) {
}
