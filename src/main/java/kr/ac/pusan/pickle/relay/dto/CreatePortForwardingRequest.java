package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.relay.PortMappingProto;

/**
 * Contract op {@code createVmPortForwarding} body. The public port is never
 * chosen by the user — it is randomly allocated inside the relay's band.
 */
public record CreatePortForwardingRequest(
        @NotNull
        @Schema(description = "프로토콜 (tcp | udp)")
        PortMappingProto proto,
        @NotNull @Min(1) @Max(65535)
        @Schema(description = "VM 내부에서 노출할 대상 포트 (1~65535)")
        Integer targetPort) {
}
