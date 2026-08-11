package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdateLlmKey}: what the owner may change about a key
 * after it exists.
 *
 * <p>Both members are optional and absent means "leave it alone", so a client
 * that only wants to flip recording does not have to resend the name it is not
 * changing.
 */
public record UpdateLlmKeyRequest(
        @Schema(description = "키 이름. 생략하면 그대로 둡니다.")
        @Size(min = 1, max = 100, message = "키 이름은 1자 이상 100자 이하여야 합니다.")
        @Nullable String name,

        @Schema(description = "사용 목적. 생략하면 그대로 둡니다.")
        @Size(max = 2000, message = "사용 목적은 2000자 이하여야 합니다.")
        @Nullable String purpose,

        @Schema(description = """
                요청·응답 본문 기록 여부. 기본값은 꺼짐이며, 켜면 이 키로 보낸 프롬프트와 \
                응답이 수집됩니다. 생략하면 그대로 둡니다.""")
        @Nullable Boolean recordBodies) {
}
