package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;

/** Contract schema {@code SignupRequest}. */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        // The pattern also accepts @pnuops.com — seeded operator accounts and the
        // end-to-end smokes live there. The message names only the university domain
        // on purpose: applicants have no reason to hold an internal address, so
        // advertising it would invite a signup path that is not for them. Do not
        // narrow the pattern to match the message.
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@(pusan\\.ac\\.kr|pnuops\\.com)$",
                message = "@pusan.ac.kr 이메일만 가입할 수 있습니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
        String password,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name,

        // Consent completeness (every current document) is validated server-side.
        @NotEmpty(message = "약관 동의가 필요합니다.")
        @Valid
        List<ConsentInput> consents) {
}
