package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Contract op {@code updateVmGatewayBlock} body. {@code reason} lands in the
 * VM event and audit detail (no user notification is sent).
 */
public record VmGatewayBlockUpdateRequest(
        @NotNull
        @Schema(description = "true = SSH 게이트웨이와 웹 터미널 차단, false = 차단 해제")
        Boolean blocked,
        @Nullable @Size(max = 200)
        @Schema(description = "차단이나 해제 사유(선택). VM 이벤트와 감사 기록에 포함")
        String reason) {
}
