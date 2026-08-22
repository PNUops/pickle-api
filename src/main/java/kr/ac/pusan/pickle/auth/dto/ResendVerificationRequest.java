package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of POST /auth/resend-verification (contract restricts the domain). */
public record ResendVerificationRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        // The pattern also accepts @pnuops.com — seeded operator accounts and the
        // end-to-end smokes live there. The message names only the university domain
        // on purpose: applicants have no reason to hold an internal address, so
        // advertising it would invite a signup path that is not for them. Do not
        // narrow the pattern to match the message.
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@(pusan\\.ac\\.kr|pnuops\\.com)$",
                message = "@pusan.ac.kr 이메일만 사용할 수 있습니다.")
        String email) {
}
