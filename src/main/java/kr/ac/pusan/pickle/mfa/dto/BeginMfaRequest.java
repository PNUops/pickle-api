package kr.ac.pusan.pickle.mfa.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /me/mfa/totp} — password re-auth to start enrollment. */
public record BeginMfaRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password) {
}
