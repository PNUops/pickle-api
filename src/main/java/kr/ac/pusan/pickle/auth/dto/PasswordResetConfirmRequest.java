package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of {@code POST /auth/password-reset/confirm}. */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "재설정 링크를 확인해 주세요.")
        String token,

        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 입력해 주세요.")
        String newPassword) {
}
