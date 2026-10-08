package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/roster/resolve body. */
public record ResolveRosterRequest(
        @Schema(description = "확인할 학번 목록. 1건에서 500건까지이며, 결과는 같은 순서로 옵니다.")
        @NotNull(message = "확인할 학번을 한 건 이상 입력해 주세요.")
        @Size(min = 1, max = 500, message = "한 번에 1건에서 500건까지 확인할 수 있습니다.")
        List<String> studentNos,

        @Schema(description = "신청을 낼 기관. 워크스페이스 소유자가 아니라 이 기관의 신청을 승인할 수 있는 관리자로서 "
                + "확인할 때 보냅니다.")
        @Nullable UUID orgId) {
}
