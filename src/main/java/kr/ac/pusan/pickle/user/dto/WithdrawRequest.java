package kr.ac.pusan.pickle.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body of {@code POST /me/withdraw}. {@code totpCode}/{@code
 * recoveryCode} are accepted now but only enforced once 2FA lands.
 */
public record WithdrawRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password,

        @Pattern(regexp = "^[0-9]{6}$", message = "TOTP 코드는 6자리 숫자입니다.")
        String totpCode,

        String recoveryCode) {
}
