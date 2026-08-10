package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body of {@code POST /auth/password-reset}. */
public record PasswordResetRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@(pusan\\.ac\\.kr|pnuops\\.com)$",
                message = "@pusan.ac.kr 이메일만 사용할 수 있습니다.")
        String email) {
}
