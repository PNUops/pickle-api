package kr.ac.pusan.pickle.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Contract schema {@code ReverifyRequest} (v0.24.0 sudo-mode). */
public record ReverifyRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password) {
}
