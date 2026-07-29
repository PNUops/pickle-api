package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import kr.ac.pusan.pickle.relay.PortForwardApplyState;
import kr.ac.pusan.pickle.relay.PortMappingProto;
import kr.ac.pusan.pickle.relay.PortMappingStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code PortForwardingView} — one VM port forwarding. */
public record PortForwardingView(
        Long id,
        @Schema(description = "프로토콜 (tcp | udp)")
        PortMappingProto proto,
        @Schema(description = "릴레이에서 열린 공개 포트")
        int publicPort,
        @Nullable
        @Schema(description = "접속에 사용할 공개 호스트. 아직 설정 전이면 null")
        String publicHost,
        @Schema(description = "VM 내부 대상 포트")
        int targetPort,
        @Schema(description = "매핑 상태 (SUSPENDED = 관리자·자동 정지)")
        PortMappingStatus status,
        @Schema(description = "릴레이 반영 상태 (PENDING = 대기, ACTIVE = 활성, FAILED = 실패)")
        PortForwardApplyState applyState,
        Instant createdAt) {
}
