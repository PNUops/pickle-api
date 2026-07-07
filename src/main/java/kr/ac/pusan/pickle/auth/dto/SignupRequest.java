package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Contract schema {@code SignupRequest}. */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@pusan\\.ac\\.kr$",
                message = "@pusan.ac.kr 이메일만 가입할 수 있습니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 10, max = 72, message = "비밀번호는 10자 이상 72자 이하여야 합니다.")
        String password,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name) {
}
