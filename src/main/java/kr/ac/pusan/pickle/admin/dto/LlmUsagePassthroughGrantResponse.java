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
        @Schema(description = "기능 권한 값이며 chat, images, embeddings, rerank, speech, transcriptions, batches 중 하나예요. 요청 본문으로 "
                + "판정하는 기능(web_search, pdf_ocr)은 사용량 이벤트가 경로만 기록해 사용을 셀 수 "
                + "없으므로 행을 제공하지 않아요.")
        String capability,
        @Schema(description = "지금 이 기능을 부여받은 키 수")
        @org.jspecify.annotations.Nullable Long grantedKeys,
        @Schema(description = """
                선택 기간에 이 기능의 경로를 호출한 키 수예요. grantedKeys는 현재 부여 상태이므로 그 부분집합을 뜻하지 않아요. 사용 뒤 권한을 회수한 키도 포함해 grantedKeys보다 클 수 있어요.""")
        long usedKeys,
        @Schema(description = "이 기능이 여는 경로들로 들어온 요청 수")
        long requests) {
}
