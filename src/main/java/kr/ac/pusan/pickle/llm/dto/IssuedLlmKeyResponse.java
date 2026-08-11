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
                발급된 API Key 평문. **이 응답에서만 볼 수 있습니다** — 서버에는 해시만 \
                저장되며 다시 조회할 수 없습니다. 분실하면 재발급해야 합니다.""")
        String token,

        @Schema(description = "만료 시각. 없으면 만료되지 않습니다.")
        @Nullable Instant expiresAt) {

    public static IssuedLlmKeyResponse of(LlmApiKey key, String plaintext) {
        return new IssuedLlmKeyResponse(key.getPublicId(), key.getName(), plaintext,
                key.getExpiresAt());
    }
}
