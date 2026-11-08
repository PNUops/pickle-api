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
        @Schema(description = "조회 시각의 종류별 상태예요. LLM 키는 정확한 만료 시각을 반영하며 다른 종류는 저장 상태예요.") String status,
        AdminResourceDeadlineKind deadlineKind,
        @Schema(description = "현재 주 기한이에요. 날짜 종료는 다음 날 0시(KST)로 정규화해요. 이 시각이 지나도 정지나 회수, 삭제 완료를 뜻하지 않아요.") Instant deadlineAt,
        @Schema(description = "해당 날짜를 포함하는 종료일이에요. VM과 GPU 승인기간에 제공하며, LLM 키는 실제 만료가 KST 0시일 때만 전날을 제공해요. 임의 시각의 기한에는 null이에요.") @Nullable LocalDate inclusiveEndDate,
        @Schema(description = "서버 관측 시각에 주 기한이 지났는지 나타내요. 실제 후속 처리 상태와는 별개예요.") boolean overdue,
        @Schema(description = "기한 판정에 사용한 서버 시각이며 마이크로초 정밀도예요.") Instant observedAt,
        @Nullable UUID workspaceId,
        @Nullable String workspaceName,
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Schema(description = "GPU의 저장된 연결 상태이며 다른 종류는 null이에요.") @Nullable String connectionStatus,
        AdminResourceDeadlineDates dates) { }
