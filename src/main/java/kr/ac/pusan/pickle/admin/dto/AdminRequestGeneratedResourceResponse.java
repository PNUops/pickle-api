package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/** The exact persisted resource association; no inferred name-based identity. */
public record AdminRequestGeneratedResourceResponse(UUID id, ResourceType type, String name,
        @Schema(description = "종류별 서버 상태. VM은 CREATING/ERROR/RUNNING 등 VM 상태, "
                + "LLM 키는 만료를 반영한 상태, GPU는 할당 상태, 도메인은 원래 행의 상태입니다. "
                + "서로 다른 상태를 완료 하나로 합치지 않습니다.")
        String state, UUID orgId, UUID workspaceId, @Nullable UUID recipientId,
        @Nullable UUID nodeId, @Nullable String nodeName,
        @Schema(description = "현재 종류별 관리자 상세 조회 가능 여부. 도메인의 REMOVED 이력은 목록 범위에서 관측하되 상세는 열리지 않습니다.")
        boolean canReadDetail,
        @Nullable Instant releasedAt,
        @Nullable Instant renewDueAt,
        @Nullable Instant reservedUntil,
        Instant createdAt,
        @Schema(description = "서버 기록이 갱신된 시각. 외부 시스템 적용이나 연결 성공을 측정한 시각이 아닙니다.")
        Instant recordUpdatedAt) { }
