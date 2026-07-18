package kr.ac.pusan.pickle.sshgw;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.sshgw.dto.SessionRequest;
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
 * {@code /internal/sshgw/session}, gate C/C-2). sshpiperd calls this from its
 * {@code PipeStart} callback — <b>after</b> signature verification (public-key)
 * or password acceptance — so it is the one point where a per-user attribution
 * is sound. It is the record G6 requires.
 *
 * <p><b>Distinct-owner rule (gate C-2).</b> {@code PipeStart} does not reveal
 * which key actually signed, so the gateway forwards the full set of
 * route-allowed candidate fingerprints. Attribution:
 * <ul>
 *   <li>all candidates resolve to <b>one</b> owner → {@code actor} = that user
 *       (the signer is necessarily one of them; they share the owner), and their
 *       candidate keys' {@code last_used_at} is bumped;</li>
 *   <li>candidates span <b>two or more</b> owners → {@code actor} = null
 *       ({@code ambiguous}) — this closes the framing vector where a member
 *       offers a fellow member's public key alongside their own; no bump;</li>
 *   <li><b>zero</b> resolve (revoked mid-connection) / <b>password</b> path →
 *       {@code actor} = null.</li>
 * </ul>
 *
 * <p>Fire-and-forget and best-effort: a race is logged, never surfaced as a 5xx
 * that would tear down an already-live session.</p>
 */
@Service
public class SshGatewaySessionService {

    private static final Logger log = LoggerFactory.getLogger(SshGatewaySessionService.class);
    private static final String AUTH_PUBLICKEY = "publickey";

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
     * Writes the {@code sshgw.session} audit for an established session by the
     * distinct-owner rule. Never throws — resolution misses and write failures
     * are logged as best-effort.
     */
    @Transactional(readOnly = true)
    public void recordSession(SessionRequest request, String gatewayPeer) {
        try {
            Long vmId = vmRepository.findByHostname(request.slug()).map(Vm::getId).orElse(null);
            Map<String, Object> detail = baseDetail(request, gatewayPeer);
            Long actorId = null;

            if (AUTH_PUBLICKEY.equals(request.authMethod())) {
                List<UserSshKey> resolved = resolveCandidates(request.candidateFingerprints());
                Set<Long> owners = new LinkedHashSet<>();
                for (UserSshKey key : resolved) {
                    owners.add(key.getUserId());
                }
                if (owners.size() == 1) {
                    // Sound attribution: the signer is one of these keys, all one owner.
                    actorId = owners.iterator().next();
                    detail.put("userId", actorId);
                    detail.put("fingerprints", fingerprintsOf(resolved));
                    detail.put("keyIds", keyIdsOf(resolved));
                    bumpLastUsed(resolved);
                } else if (owners.size() >= 2) {
                    // Framing vector: candidates span owners; the plugin can't prove the
                    // signer, so attribute to no one.
                    detail.put("ambiguous", true);
                    detail.put("candidateUserIds", new ArrayList<>(owners));
                    detail.put("fingerprints", fingerprintsOf(resolved));
                } else {
                    // Zero resolve — all keys revoked mid-connection. Best-effort miss.
                    detail.put("fingerprints", nonBlank(request.candidateFingerprints()));
                    log.info("sshgw session: no candidate fingerprint resolves for slug {} "
                            + "(keys deleted mid-connection?)", request.slug());
                }
            }

            auditService.record(actorId, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_SESSION,
                    "vm", vmId, detail, request.sourceIp());
        } catch (RuntimeException e) {
            // Fire-and-forget: never let an audit failure affect the live session.
            log.warn("sshgw session audit failed (best-effort) for slug {}: {}",
                    request.slug(), e.toString());
        }
    }

    private List<UserSshKey> resolveCandidates(List<String> candidateFingerprints) {
        List<UserSshKey> resolved = new ArrayList<>();
        if (candidateFingerprints == null) {
            return resolved;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String fingerprint : candidateFingerprints) {
            if (fingerprint == null || fingerprint.isBlank() || !seen.add(fingerprint)) {
                continue;
            }
            sshKeyRepository.findByFingerprintSha256(fingerprint).ifPresent(resolved::add);
        }
        return resolved;
    }

    private void bumpLastUsed(List<UserSshKey> keys) {
        for (UserSshKey key : keys) {
            try {
                sshKeyRepository.touchLastUsedAt(key.getId(), Instant.now());
            } catch (RuntimeException e) {
                log.debug("sshgw session: last_used_at bump failed for key {} (ignored)",
                        key.getId(), e);
            }
        }
    }

    private static Map<String, Object> baseDetail(SessionRequest request, String gatewayPeer) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("slug", request.slug());
        detail.put("sourceIp", request.sourceIp());
        detail.put("gatewayPeer", gatewayPeer);
        detail.put("authMethod", request.authMethod());
        if (request.connectionId() != null && !request.connectionId().isBlank()) {
            detail.put("connectionId", request.connectionId());
        }
        return detail;
    }

    private static List<String> fingerprintsOf(List<UserSshKey> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (UserSshKey key : keys) {
            out.add(key.getFingerprintSha256());
        }
        return out;
    }

    private static List<Long> keyIdsOf(List<UserSshKey> keys) {
        List<Long> out = new ArrayList<>(keys.size());
        for (UserSshKey key : keys) {
            out.add(key.getId());
        }
        return out;
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
