package kr.ac.pusan.pickle.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /me/mfa/totp/activate} — first TOTP code confirming the secret. */
public record ActivateMfaRequest(
        @NotBlank(message = "인증 코드를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{6}$", message = "인증 코드는 6자리 숫자입니다.")
        String code) {
}
