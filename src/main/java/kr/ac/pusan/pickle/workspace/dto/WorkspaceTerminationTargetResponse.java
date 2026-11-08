package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import org.jspecify.annotations.Nullable;

/** One affected request or recipient; failed recipients remain informational. */
public record WorkspaceTerminationTargetResponse(
        Kind kind,
        UUID requestId,
        String requestName,
        RequestStatus requestStatus,
        ResourceType type,
        UUID orgId,
        String orgName,
        boolean requestReadable,
        @Nullable UUID recipientId,
        @Nullable UUID invitationId,
        @Nullable UUID userId,
        @Nullable String name,
        @Schema(description = "초대 대상의 이메일이나 학번이며, 현재 OWNER 또는 지원 쓰기 권한 범위에서만 제공해요.")
        @Nullable String invitee,
        boolean userReadable,
        @Nullable RequestRecipientStatus recipientStatus,
        @Nullable String reason,
        @Nullable UUID resourceId,
        @Schema(description = "현재 종료 경로의 취소 대상 여부예요. FAILED 이력은 false예요.")
        boolean willClose) {

    @Schema(name = "WorkspaceTerminationTargetKind")
    public enum Kind { REQUEST, RECIPIENT }
}
