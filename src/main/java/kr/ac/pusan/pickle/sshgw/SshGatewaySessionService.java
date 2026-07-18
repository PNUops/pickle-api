package kr.ac.pusan.pickle.sshgw;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.sshgw.dto.RouteRequest;
import kr.ac.pusan.pickle.sshkey.UserSshKey;
import kr.ac.pusan.pickle.sshkey.UserSshKeyRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the authenticated SSH session audit (docs/api/internal.md Link 1
 * {@code /internal/sshgw/session}, gate-C fix). sshpiperd calls this from its
 * {@code PipeStart} callback — <b>after</b> downstream signature verification
 * (public-key) or password acceptance — so unlike the route lookup this is the
 * one point where a per-user attribution is sound (the signature proved
 * possession). It is the record G6 requires.
 *
 * <p>Fire-and-forget and best-effort: a race where the key/membership vanished
 * between the route lookup and PipeStart is logged, never surfaced as a 5xx that
 * would tear down an already-live session. The session is already authenticated;
 * this call only writes the audit row.</p>
 */
@Service
public class SshGatewaySessionService {

    private static final Logger log = LoggerFactory.getLogger(SshGatewaySessionService.class);

    private final VmRepository vmRepository;
    private final UserSshKeyRepository sshKeyRepository;
    private final AuditService auditService;

    public SshGatewaySessionService(VmRepository vmRepository,
            UserSshKeyRepository sshKeyRepository, AuditService auditService) {
        this.vmRepository = vmRepository;
        this.sshKeyRepository = sshKeyRepository;
        this.auditService = auditService;
    }

    /**
     * Writes the {@code sshgw.session} audit for an established session. On the
     * publickey path the actor is the (now verified) key owner and the key's
     * {@code last_used_at} is bumped; the password path carries a null actor
     * (its documented anonymity). Never throws — resolution misses and write
     * failures are logged as best-effort.
     */
    @Transactional(readOnly = true)
    public void recordSession(RouteRequest request, String gatewayPeer) {
        try {
            Long vmId = vmRepository.findByHostname(request.slug()).map(Vm::getId).orElse(null);
            Long actorId = null;
            Long keyId = null;
            if (RouteRequest.AUTH_PUBLICKEY.equals(request.authMethod())
                    && request.publicKeyFingerprint() != null
                    && !request.publicKeyFingerprint().isBlank()) {
                UserSshKey key = sshKeyRepository
                        .findByFingerprintSha256(request.publicKeyFingerprint()).orElse(null);
                if (key != null) {
                    actorId = key.getUserId();
                    keyId = key.getId();
                    touchLastUsed(key.getId());
                } else {
                    // Key deleted between the route lookup and PipeStart — record
                    // the session (best-effort) with a null actor; do not fail.
                    log.info("sshgw session: fingerprint no longer resolves for slug {} "
                            + "(key deleted mid-connection?)", request.slug());
                }
            }
            auditService.record(actorId, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_SESSION,
                    "vm", vmId, detail(request, gatewayPeer, keyId), request.sourceIp());
        } catch (RuntimeException e) {
            // Fire-and-forget: never let an audit failure affect the live session.
            log.warn("sshgw session audit failed (best-effort) for slug {}: {}",
                    request.slug(), e.toString());
        }
    }

    private void touchLastUsed(Long keyId) {
        try {
            sshKeyRepository.touchLastUsedAt(keyId, Instant.now());
        } catch (RuntimeException e) {
            log.debug("sshgw session: last_used_at bump failed for key {} (ignored)", keyId, e);
        }
    }

    private static Map<String, Object> detail(RouteRequest request, String gatewayPeer, Long keyId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("slug", request.slug());
        detail.put("sourceIp", request.sourceIp());
        detail.put("gatewayPeer", gatewayPeer);
        detail.put("authMethod", request.authMethod());
        if (request.publicKeyFingerprint() != null && !request.publicKeyFingerprint().isBlank()) {
            detail.put("fingerprint", request.publicKeyFingerprint());
        }
        if (keyId != null) {
            detail.put("keyId", keyId);
        }
        if (request.connectionId() != null && !request.connectionId().isBlank()) {
            detail.put("connectionId", request.connectionId());
        }
        return detail;
    }
}
