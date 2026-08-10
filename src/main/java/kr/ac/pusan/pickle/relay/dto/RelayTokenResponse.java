package kr.ac.pusan.pickle.relay.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Contract op {@code issueAdminRelayToken} response. The plaintext token is
 * returned exactly once — only its hash is stored, so it can never be shown
 * again (re-issue replaces it).
 */
public record RelayTokenResponse(
        UUID relayId,
        @Schema(description = "새 동기화 토큰(64자 hex). 이 응답에서만 확인 가능하며 저장되지 않습니다")
        String token) {
}
