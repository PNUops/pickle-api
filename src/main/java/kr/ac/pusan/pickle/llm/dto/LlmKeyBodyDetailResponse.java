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
        @Schema(description = "이 기록과 같은 요청에서 나온 사용량 이벤트 ID예요.")
        String eventUuid,
        Instant requestedAt,
        Instant receivedAt,
        @Schema(description = "프롬프트가 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean requestTruncated,
        @Schema(description = "응답이 길이 제한에 걸려 앞부분만 기록됐는지 여부")
        boolean responseTruncated,
        int requestBytes,
        int responseBytes,
        @Schema(description = "false면 저장된 본문을 읽을 수 없어요.")
        boolean readable,
        @Schema(description = "보낸 프롬프트이며 보통 messages 배열이에요. 길이 제한에 "
                + "걸리면 앞부분을 담은 문자열이에요. 읽을 수 없거나 기록하지 않았으면 null이에요.")
        @Nullable JsonNode request,
        @Schema(description = "받은 응답 텍스트이며, 읽을 수 없거나 기록하지 않았으면 null이에요.")
        @Nullable String response) {
}
