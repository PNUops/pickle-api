package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Contract schema {@code LlmKeyUsagePoint}: one KST calendar day. */
public record LlmKeyUsagePointResponse(
        LocalDate day,
        @Schema(description = "이 날 이 Key로 들어온 요청 수 — 거부된 것과 실패한 것을 포함합니다.")
        long requests,
        @Schema(description = "정상 응답한 요청 수")
        long succeeded,
        @Schema(description = "한도에 걸려 거부된 요청 수. 계속 0이 아니면 한도 상향을 신청할 때입니다.")
        long rateLimited,
        @Schema(description = "그 밖의 사유로 실패한 요청 수 (업스트림 오류, 시간 초과, 잘못된 요청 등)")
        long failed,
        @Schema(description = "입력 토큰 합")
        long inputTokens,
        @Schema(description = "출력 토큰 합")
        long outputTokens,
        @Schema(description = """
                토큰 수가 실측이 아니라 추정인 요청 수. 스트리밍 응답에서 업스트림이 사용량을 \
                주지 않으면 게이트웨이가 추정하므로, 이 값이 크면 위 토큰 합도 그만큼 추정입니다.""")
        long estimatedRequests) {
}
