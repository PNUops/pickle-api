package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract op {@code suspendAdminPortMapping} body. */
public record SuspendPortMappingRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "정지 사유예요. VM에 직접 소유자나 편집자로 등록된 사용자와 소유 워크스페이스 소유자 중 활성 계정을 대상으로 한 알림에 포함해요.")
        String reason) {
}
