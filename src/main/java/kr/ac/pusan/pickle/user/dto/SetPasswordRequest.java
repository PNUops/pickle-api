package kr.ac.pusan.pickle.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contract schema {@code SetPasswordRequest} — POST /me/password.
 *
 * <p>Carries no current password, and that is the point: this is the endpoint
 * for an account created through Google, which has never had one. What stands
 * in for it is {@code @RequireReauth} — the caller has proved, within the last
 * ten minutes, that they are the holder, by password or by Google. Without
 * that gate an access token alone would be enough to plant a password on a
 * hijacked account and keep it after the token expired.
 */
public record SetPasswordRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
        String newPassword) {
}
