package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One person a request asks a resource for: a member of the workspace, or a
 * pending invitation into it. Exactly one of the two is set.
 */
@Schema(description = "리소스를 받을 대상자 한 명. userId와 invitationId 중 정확히 하나를 보냅니다.")
public record CreateRequestRecipient(
        @Schema(description = "워크스페이스의 활성 구성원.")
        @Nullable UUID userId,
        @Schema(description = "워크스페이스의 대기 중인 초대. 초대받은 사람이 가입하면 그때 리소스를 만듭니다.")
        @Nullable UUID invitationId) {
}
