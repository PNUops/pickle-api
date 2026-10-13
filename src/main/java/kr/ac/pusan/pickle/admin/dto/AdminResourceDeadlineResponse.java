package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/** One resource and one primary deadline, with separate stored state and date context. */
public record AdminResourceDeadlineResponse(
        UUID id,
        ResourceType type,
        String name,
        @Schema(description = "DB에 저장된 종류별 상태") String sourceStatus,
        @Schema(description = "조회 시각의 종류별 상태. LLM 키는 정확한 만료 시각을 반영하며 다른 종류는 저장 상태입니다.") String status,
        AdminResourceDeadlineKind deadlineKind,
        @Schema(description = "현재 주 기한. 날짜 종료는 다음 날 0시(KST)로 정규화합니다. 이 시각 경과는 정지·회수·삭제 완료를 뜻하지 않습니다.") Instant deadlineAt,
        @Schema(description = "그날까지 포함하는 종료일. VM·GPU 승인기간에 표시하며, LLM 키는 만료가 실제 KST 0시일 때만 전날을 표시합니다. 임의 시각의 기한에는 null입니다.") @Nullable LocalDate inclusiveEndDate,
        @Schema(description = "서버 관측 시각에 주 기한이 경과했는지. 실제 후속 처리 상태와 별개입니다.") boolean overdue,
        @Schema(description = "기한 판정과 함께 사용한 서버 시각. 마이크로초 정밀도입니다.") Instant observedAt,
        @Nullable UUID workspaceId,
        @Nullable String workspaceName,
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Schema(description = "GPU의 저장 연결 상태. 다른 종류는 null입니다.") @Nullable String connectionStatus,
        AdminResourceDeadlineDates dates) { }
