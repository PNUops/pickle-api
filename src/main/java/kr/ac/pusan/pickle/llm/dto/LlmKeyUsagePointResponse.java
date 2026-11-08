package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Contract schema {@code LlmKeyUsagePoint}: one KST calendar day. */
public record LlmKeyUsagePointResponse(
        LocalDate day,
        @Schema(description = "이 날 이 키로 기록된 요청 수이며, 거절되거나 실패한 요청도 포함해요.")
        long requests,
        @Schema(description = "정상 응답한 요청 수")
        long succeeded,
        @Schema(description = "한도에 걸려 거절된 요청 수예요.")
        long rateLimited,
        @Schema(description = "그 밖의 사유로 실패한 요청 수 (업스트림 오류, 시간 초과, 잘못된 요청 등)")
        long failed,
        @Schema(description = "입력 토큰 합")
        long inputTokens,
        @Schema(description = "출력 토큰 합")
        long outputTokens,
        @Schema(description = """
                토큰 수에 추정값이 포함된 요청 수예요. 스트리밍 응답에서 업스트림이 사용량을 \
                제공하지 않아 Gateway가 추정하는 경우 등이 포함돼요.""")
        long estimatedRequests,
        @Schema(description = """
                입력 토큰 중 공급자가 캐시에서 읽어 처리한 부분이에요. 입력 토큰의 부분집합이므로 \
                inputTokens에 더하면 중복 집계돼요.""")
        long cachedInputTokens,
        @Schema(description = """
                출력 토큰 중 모델이 답을 내기 전에 추론에 쓴 부분이에요. 출력 토큰의 부분집합이므로 \
                outputTokens에 더하면 중복 집계돼요.""")
        long reasoningTokens,
        @Schema(description = """
                이 날 응답에 보고된 이미지 수예요. 요청한 장수가 아닌 보고된 응답 값을 집계해요.""")
        long imageCount,
        @Schema(description = "스트리밍으로 받은 요청 수")
        long streamedRequests) {
}
