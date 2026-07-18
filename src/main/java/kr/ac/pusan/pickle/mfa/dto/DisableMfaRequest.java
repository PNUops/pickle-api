package kr.ac.pusan.pickle.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /me/mfa/disable} — password plus a TOTP code or a recovery
 * code (exactly one of the two). The exactly-one rule is enforced in the service
 * (422) so the message can be specific.
 */
public record DisableMfaRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password,

        @Pattern(regexp = "^[0-9]{6}$", message = "인증 코드는 6자리 숫자입니다.")
        String code,

        String recoveryCode) {
}
