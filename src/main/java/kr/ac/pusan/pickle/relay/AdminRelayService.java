package kr.ac.pusan.pickle.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.RelayProperties;
import kr.ac.pusan.pickle.relay.dto.AdminRelayView;
import kr.ac.pusan.pickle.relay.dto.RelayTokenResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin relay observability + token lifecycle (contract ops
 * {@code listAdminRelays} / {@code issueAdminRelayToken}).
 */
@Service
public class AdminRelayService {

    private final RelayRepository relayRepository;
    private final PortMappingRepository portMappingRepository;
    private final RelayProperties relayProperties;
    private final AuditService auditService;

    public AdminRelayService(RelayRepository relayRepository,
            PortMappingRepository portMappingRepository, RelayProperties relayProperties,
            AuditService auditService) {
        this.relayRepository = relayRepository;
        this.portMappingRepository = portMappingRepository;
        this.relayProperties = relayProperties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AdminRelayView> list() {
        Instant lostBefore = Instant.now()
                .minus(Duration.ofSeconds(3L * relayProperties.pollIntervalSeconds()));
        return relayRepository.findAllByOrderByIdAsc().stream().map(relay -> {
            long mappingCount = portMappingRepository.countByRelayId(relay.getId());
            // Every row occupies a distinct port (cross-proto exclusive
            // allocation), so the row count IS the used-port count.
            int usagePercent = (int) (mappingCount * 100 / relay.bandSize());
            boolean contactLost = relay.getLastContactAt() != null
                    && relay.getLastContactAt().isBefore(lostBefore);
            return new AdminRelayView(relay.getPublicId(), relay.getName(), relay.getPublicHost(),
                    relay.getPortBandStart(), relay.getPortBandEnd(), relay.isEnabled(),
                    relay.getTokenHash() != null, relay.getMappingGeneration(),
                    relay.getAppliedGeneration(), relay.getLastContactAt(), contactLost,
                    relay.getAgentVersion(), relay.getLastError(), mappingCount, usagePercent);
        }).toList();
    }

    /**
     * Issues (or rotates) the relay's sync token: the plaintext leaves in this
     * response exactly once, only the hash persists, and the token itself is
     * never logged or audited.
     */
    @Transactional
    public RelayTokenResponse issueToken(AuthenticatedUser actor, UUID relayId, String ip) {
        Relay relay = relayRepository.findByPublicId(relayId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "해당 릴레이가 존재하지 않습니다."));
        String token = RelayTokens.newToken();
        relay.setTokenHash(RelayTokens.sha256Hex(token));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.RELAY_TOKEN_ISSUE, "relay", relay.getPublicId(),
                Map.of("relayName", relay.getName(), "rotated", true), ip);
        return new RelayTokenResponse(relay.getPublicId(), token);
    }
}
