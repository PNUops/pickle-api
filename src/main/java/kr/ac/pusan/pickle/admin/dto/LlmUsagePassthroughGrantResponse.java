package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One capability, the keys that hold it, and the keys that used it.
 *
 * <p>This does not answer how much was used; the route breakdown beside it
 * already does. It answers whether a capability is being granted and then left
 * alone, which is the evidence for changing what the approval screen offers by
 * default.
 *
 * <p>A capability is not a route. {@code images} opens both image generation
 * and the image catalogue lookup, so a key counted here as having used it may
 * have used either.
 */
public record LlmUsagePassthroughGrantResponse(
        @Schema(description = "기능 권한 값. images 또는 embeddings입니다.")
        String capability,
        @Schema(description = "지금 이 기능을 부여받은 키 수")
        long grantedKeys,
        @Schema(description = """
                이 기간에 이 기능이 여는 경로를 실제로 호출한 키 수. **grantedKeys의 \
                부분집합이 아닙니다** — 부여는 지금 상태이고 호출은 지나간 사실이라, 쓰고 \
                나서 권한이 회수된 키가 여기에만 남아 grantedKeys보다 클 수 있습니다. \
                호출한 쪽을 부여로 걸러 내지 않는 것은 그렇게 하면 그 요청이 아래 requests \
                에는 있고 이 수에는 없어 두 칸이 서로를 반박하기 때문입니다.""")
        long usedKeys,
        @Schema(description = "이 기능이 여는 경로들로 들어온 요청 수")
        long requests) {
}
