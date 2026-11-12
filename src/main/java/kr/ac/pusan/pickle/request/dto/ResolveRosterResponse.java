package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.request.RosterEntryStatus;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/roster/resolve response. */
public record ResolveRosterResponse(
        @Schema(description = "요청한 학번마다 한 건씩 요청과 같은 순서로 반환해요.")
        List<RosterEntryResult> results) {

    /** Contract schema {@code RosterEntryResult}: one 학번 and where it stands. */
    public record RosterEntryResult(
            @Schema(description = "요청에 쓴 학번(앞뒤 공백 제거).")
            String studentNo,

            @Schema(description = "확인 결과.")
            RosterEntryStatus status,

            @Schema(description = "해당 학번의 구성원 계정이에요. MEMBER일 때만 있고 구성원이 아닌 REGISTERED에는 없어요.")
            @Nullable UUID userId,

            @Schema(description = "해당 학번으로 대기 중인 초대예요. INVITED일 때만 있어요.")
            @Nullable UUID invitationId,

            @Schema(description = "구성원의 이름이에요. MEMBER일 때만 있고 구성원이 아닌 REGISTERED에는 없어요.")
            @Nullable String name) {
    }
}
