package kr.ac.pusan.pickle.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of {@code PUT /me/password}. */
public record ChangePasswordRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        @Size(min = 10, max = 72, message = "비밀번호는 10자 이상 72자 이하여야 합니다.")
        String newPassword) {
}
