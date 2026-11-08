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
        @Schema(description = "키 이름이며, 생략하면 그대로 둬요.")
        @Size(min = 1, max = 100, message = "키 이름은 1~100자로 입력해 주세요.")
        @Nullable String name,

        @Schema(description = "사용 목적이며, 생략하면 그대로 둬요.")
        @Size(max = 2000, message = "사용 목적은 2000자 이내로 입력해 주세요.")
        @Nullable String purpose,

        @Schema(description = """
                요청과 응답 본문 기록 여부예요. 켠 설정이 Gateway에 반영되고 전역 본문 기록이 켜져 있으면 \
                chat 요청의 프롬프트와 응답을 수집할 수 있어요. 생략하거나 null이면 유지해요. 꺼도 기존 기록은 삭제하지 않아요.""")
        @Nullable Boolean recordBodies) {
}
