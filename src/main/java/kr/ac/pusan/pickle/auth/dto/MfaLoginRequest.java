package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /auth/mfa}. Exactly one of {@code code}/{@code recoveryCode}
 * must be present — enforced in the service (422) so the message is specific.
 */
public record MfaLoginRequest(
        @NotBlank(message = "인증 세션 토큰이 필요합니다.")
        String mfaToken,

        @Pattern(regexp = "^[0-9]{6}$", message = "인증 코드는 6자리 숫자입니다.")
        String code,

        String recoveryCode) {
}
