package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Contract schema {@code LlmKeyBodyDetail}: one captured exchange in full.
 *
 * <p>{@code request} has two shapes and a reader must handle both. Normally it
 * is the messages array exactly as the client sent it. When the prompt hit its
 * length cap it is a JSON <i>string</i> holding the prefix instead, because
 * cutting a JSON array mid-way produces nothing a parser will take. Decide
 * which by looking at the value, never by inferring it from
 * {@code requestTruncated} — that flag says the tail was cut, not what the
 * value is, and a future capture could be neither an array nor truncated.
 */
public record LlmKeyBodyDetailResponse(
        UUID id,
        @Schema(description = "이 기록과 같은 요청에서 나온 사용량 이벤트의 식별자입니다.")
        String eventUuid,
        Instant requestedAt,
        Instant receivedAt,
        @Schema(description = "프롬프트가 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean requestTruncated,
        @Schema(description = "응답이 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean responseTruncated,
        int requestBytes,
        int responseBytes,
        @Schema(description = "false면 이 기록을 푸는 암호화 키가 서버에 없어 본문을 읽을 수 없습니다.")
        boolean readable,
        @Schema(description = "보낸 프롬프트. 보통은 messages 배열 그대로이고, 길이 제한에 "
                + "걸린 경우에는 앞부분을 담은 문자열입니다. 기록되지 않았으면 null입니다.")
        @Nullable JsonNode request,
        @Schema(description = "받은 응답 텍스트. 기록되지 않았으면 null입니다.")
        @Nullable String response) {
}
