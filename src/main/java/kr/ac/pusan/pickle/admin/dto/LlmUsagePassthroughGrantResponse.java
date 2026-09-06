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
        @Schema(description = "이 기능을 부여받은 키 수")
        long grantedKeys,
        @Schema(description = "그중 이 기간에 실제로 호출한 키 수")
        long usedKeys,
        @Schema(description = "이 기능이 여는 경로들로 들어온 요청 수")
        long requests) {
}
