package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code IssuedLlmKey}: the answer to issuing or rotating a
 * key, and the only response anywhere that carries a plaintext.
 *
 * <p>It is not stored, and no other endpoint can produce it again — what the
 * database holds is a hash. A client that loses this response has to rotate,
 * which is the behaviour the requirements ask for rather than a limitation of
 * this one.
 */
public record IssuedLlmKeyResponse(
        @Schema(description = "키 식별자")
        UUID id,

        @Schema(description = "키 이름")
        String name,

        @Schema(description = """
                발급된 API 키 평문이며 **이 응답에서만 볼 수 있어요.** 서버가 평문 대신 해시를 \
                저장하므로 다시 조회할 수 없어요. 잃어버렸다면 재발급해 주세요.""")
        String token,

        @Schema(description = "만료 시각이며, null이면 만료가 없어요.")
        @Nullable Instant expiresAt) {

    public static IssuedLlmKeyResponse of(LlmApiKey key, String plaintext) {
        return new IssuedLlmKeyResponse(key.getPublicId(), key.getName(), plaintext,
                key.getExpiresAt());
    }
}
