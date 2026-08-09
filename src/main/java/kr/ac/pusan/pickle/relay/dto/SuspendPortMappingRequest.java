package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract op {@code suspendAdminPortMapping} body. */
public record SuspendPortMappingRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "정지 사유 (소유 워크스페이스에 알림으로 전달)")
        String reason) {
}
