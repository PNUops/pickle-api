package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of {@code POST /admin/users/{userId}/disable}. */
public record DisableUserRequest(
        @NotBlank(message = "비활성화 사유를 입력해 주세요.")
        @Size(min = 1, max = 500, message = "사유는 500자 이하여야 합니다.")
        String reason) {
}
