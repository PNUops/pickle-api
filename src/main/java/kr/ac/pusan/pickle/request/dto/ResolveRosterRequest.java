package kr.ac.pusan.pickle.request.dto;

import kr.ac.pusan.pickle.access.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/roster/resolve body. */
public record ResolveRosterRequest(
        @Schema(description = "확인할 학번 목록이에요. 한 번에 1~500건이며 요청과 같은 순서로 결과를 돌려줘요. "
                + "한 시간에 확인할 수 있는 학번은 모두 합쳐 2000건이에요.")
        @NotNull(message = "확인할 학번을 한 건 이상 입력해 주세요.")
        @Size(min = 1, max = 500, message = "한 번에 1~500건을 입력해 주세요.")
        List<@Size(max = 64, message = "학번은 64자 이내로 입력해 주세요.") String> studentNos,

        @Schema(description = "신청을 낼 기관이에요. 워크스페이스 소유자가 아니라 이 기관의 명단 발급 업무를 가진 담당자로서 "
                + "확인할 때 보내요.")
        @Nullable UUID orgId,

        @Schema(description = "기관 권한으로 명단을 확인할 신청 종류예요. VM 또는 LLM_API_KEY만 지원해요. 워크스페이스 소유자는 생략할 수 있어요.")
        @Nullable ResourceType type) {
    public ResolveRosterRequest(List<String> studentNos, UUID orgId) { this(studentNos, orgId, null); }
}
