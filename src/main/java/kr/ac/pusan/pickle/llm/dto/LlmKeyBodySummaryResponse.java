package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyBodySummary}: one captured exchange as it
 * appears in a list, with each side cut to a preview.
 *
 * <p>The full text lives behind the detail call rather than here. A page of
 * twenty records at their caps would be several megabytes of prompt on a
 * screen where a reader opens at most one of them, and — the deciding
 * reason — a list that carried the whole text would make every list call a
 * bulk read, leaving the audit trail unable to say what was actually looked
 * at.
 */
public record LlmKeyBodySummaryResponse(
        UUID id,
        @Schema(description = "이 기록과 같은 요청에서 나온 사용량 이벤트의 식별자입니다. "
                + "리소스 id가 아니라 두 기록을 잇는 값이고, 클라이언트 로그와 대조할 때 씁니다.")
        String eventUuid,
        @Schema(description = "요청 시각. 게이트웨이가 보고한 값입니다.")
        Instant requestedAt,
        @Schema(description = "이 기록이 서버에 도착한 시각")
        Instant receivedAt,
        @Schema(description = "프롬프트가 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean requestTruncated,
        @Schema(description = "응답이 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean responseTruncated,
        @Schema(description = "기록된 프롬프트의 바이트 수")
        int requestBytes,
        @Schema(description = "기록된 응답의 바이트 수")
        int responseBytes,
        @Schema(description = "false면 이 기록을 푸는 암호화 키가 서버에 없어 본문을 읽을 수 "
                + "없습니다. 미리보기도 null입니다.")
        boolean readable,
        @Schema(description = "프롬프트 앞부분. 읽을 수 없거나 기록되지 않았으면 null입니다.")
        @Nullable String requestPreview,
        @Schema(description = "응답 앞부분. 읽을 수 없거나 기록되지 않았으면 null입니다.")
        @Nullable String responsePreview) {
}
