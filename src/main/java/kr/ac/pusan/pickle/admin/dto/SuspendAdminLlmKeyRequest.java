package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendAdminLlmKeyRequest(
        @NotBlank(message = "정지 사유를 입력해 주세요.")
        @Size(max = 500, message = "정지 사유는 500자 이하여야 합니다.")
        String reason) {
}
