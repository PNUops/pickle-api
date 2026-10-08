package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One person a request asks a resource for: a member of the workspace, a
 * pending invitation into it, or a 학번 that is resolved to one of the two at
 * submission. Exactly one of the three is set.
 */
@Schema(description = "리소스를 받을 대상자 한 명. userId, invitationId, studentNo 중 정확히 하나를 보냅니다.")
public record CreateRequestRecipient(
        @Schema(description = "워크스페이스의 활성 구성원.")
        @Nullable UUID userId,
        @Schema(description = "워크스페이스의 대기 중인 초대. 초대받은 사람이 가입하면 그때 리소스를 만듭니다.")
        @Nullable UUID invitationId,
        @Schema(description = "대상자의 학번. 그 학번의 활성 계정이 구성원이면 그 사람이 대상자가 되고, 구성원이 아니면 "
                + "제출할 때 구성원으로 추가합니다. 계정이 없으면 대기 중인 초대를 쓰거나 제출할 때 새로 초대하며, "
                + "그 사람이 가입하면 리소스를 만듭니다. 추가와 초대는 신청이 나중에 반려되거나 취소되어도 남고, "
                + "신청자의 시간당 초대 인원 한도에 포함됩니다. 개인 워크스페이스에는 쓸 수 없습니다.",
                example = "202612345")
        @Size(max = 64, message = "학번은 64자 이하여야 합니다.")
        @Nullable String studentNo) {
}
