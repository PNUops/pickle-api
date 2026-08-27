package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;

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

        // 직책·소속 학과 are optional since v0.46.0: signup asks for an account,
        // and the console collects the profile afterwards in a prompt the holder
        // can dismiss. They stay on this schema because the values are still
        // accepted when a caller has them.
        //
        // Whether 학번 is required depends on the position, so that rule and the
        // department lookup are ProfileValidator's — and they run before the
        // address is looked at, or the validation order becomes the enumeration
        // oracle the uniform 202 is meant to remove.
        @Nullable UserPosition position,

        @Size(max = 20, message = "학번은 20자 이하여야 합니다.")
        @Nullable String studentNo,

        @Size(max = 32, message = "소속 코드가 올바르지 않습니다.")
        @Nullable String departmentCode,

        // 소속, written out. A student picks a catalogue code; everyone else
        // writes it, because a 연구소 or a 부서 is not in any 학과 list. Both
        // together is the unlisted-학과 case and needs the OTHER code.
        @Size(max = 100, message = "소속은 100자 이하여야 합니다.")
        @Nullable String departmentOther,

        // Consent completeness (every current document) is validated server-side.
        @NotEmpty(message = "약관 동의가 필요합니다.")
        @Valid
        List<ConsentInput> consents) {
}
