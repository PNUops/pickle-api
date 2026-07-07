package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /auth/verify-email. */
public record VerifyEmailRequest(
        @NotBlank(message = "인증 토큰을 입력해 주세요.")
        String token) {
}
