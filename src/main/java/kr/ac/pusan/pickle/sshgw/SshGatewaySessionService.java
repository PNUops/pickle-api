package kr.ac.pusan.pickle.sshgw;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.sshgw.dto.SessionRequest;
import kr.ac.pusan.pickle.sshkey.VmSshKey;
import kr.ac.pusan.pickle.sshkey.VmSshKeyRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the authenticated SSH session audit (the internal SSH gateway route
 * contract, {@code /internal/sshgw/session}). sshpiperd calls this from its
 * {@code PipeStart} callback — <b>after</b> signature verification (public-key)
 * or password acceptance — so it is the one point where a per-user attribution
 * is sound. It is the per-user attribution the route contract requires.
 *
 * <p><b>Distinct-owner rule.</b> {@code PipeStart} does not reveal
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
 * <p><b>{@code authMethod=password} wins over any accumulated candidates.</b> A
 * connection may offer a public key (accumulating a candidate) and then actually
 * authenticate by password — e.g. an attacker offers a <i>victim's</i> public key
 * (a single candidate) that would otherwise resolve to one owner, fails to sign,
 * and falls back to the VM password on an opt-in VM. Since the session's
 * {@code authMethod} is {@code password}, the candidates are ignored entirely and
 * {@code actor} stays null — closing the single-victim-key + password-fallback
 * framing that the distinct-owner rule (two-owner-triggered) alone would miss.</p>
 *
 * <p>Fire-and-forget and best-effort: a race is logged, never surfaced as a 5xx
 * that would tear down an already-live session.</p>
 */
@Service
public class SshGatewaySessionService {

    private static final Logger log = LoggerFactory.getLogger(SshGatewaySessionService.class);
    private static final String AUTH_PUBLICKEY = "publickey";

    private final VmRepository vmRepository;
    private final VmSshKeyRepository sshKeyRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;

    public SshGatewaySessionService(VmRepository vmRepository,
            VmSshKeyRepository sshKeyRepository, AuditService auditService,
            AuditIds auditIds) {
        this.vmRepository = vmRepository;
        this.sshKeyRepository = sshKeyRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
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

            // authMethod=password wins over any accumulated candidates: the
            // candidate set is consulted ONLY on the publickey path, so a
            // password-fallback session is always keyless (actor=null), even if a
            // (victim's) public key was offered earlier on the connection.
            if (AUTH_PUBLICKEY.equals(request.authMethod())) {
                List<VmSshKey> resolved =
                        resolveCandidates(request.candidateFingerprints(), vmId);
                Set<Long> owners = new LinkedHashSet<>();
                for (VmSshKey key : resolved) {
                    owners.add(key.getUserId());
                }
                if (owners.size() == 1) {
                    // Sound attribution: the signer is one of these keys, all one owner.
                    actorId = owners.iterator().next();
                    detail.put("userId", auditIds.user(actorId));
                    detail.put("fingerprints", fingerprintsOf(resolved));
                    detail.put("keyIds", auditIds.sshKeys(keyIdsOf(resolved)));
                    bumpLastUsed(resolved);
                } else if (owners.size() >= 2) {
                    // Framing vector: candidates span owners; the plugin can't prove the
                    // signer, so attribute to no one.
                    detail.put("ambiguous", true);
                    detail.put("candidateUserIds", auditIds.users(owners));
                    detail.put("fingerprints", fingerprintsOf(resolved));
                } else {
                    // Zero resolve — all keys revoked mid-connection. Best-effort miss.
                    detail.put("fingerprints", nonBlank(request.candidateFingerprints()));
                    log.info("sshgw session: no candidate fingerprint resolves for slug {} "
                            + "(keys deleted mid-connection?)", request.slug());
                }
            }

            auditService.record(actorId, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_SESSION,
                    "vm", vmPublicId(vmId), detail, request.sourceIp());
        } catch (RuntimeException e) {
            // Fire-and-forget: never let an audit failure affect the live session.
            log.warn("sshgw session audit failed (best-effort) for slug {}: {}",
                    request.slug(), e.toString());
        }
    }

    /**
     * The candidate fingerprints that resolve to a key issued for this VM.
     *
     * <p>The gateway only forwards keys the route call accepted, so the VM filter
     * should be redundant — it is here because this side should not have to trust
     * that. Narrowing the set does not weaken the distinct-owner rule below: a VM
     * still holds one key per member, so candidates can still span owners and the
     * framing vector the rule exists for is untouched.</p>
     *
     * <p>When the slug no longer resolves to a VM the filter cannot be evaluated,
     * so it is skipped rather than dropping every candidate. Otherwise a VM
     * destroyed mid-connection would cost the session its attribution, which is
     * exactly the record worth keeping.</p>
     */
    private List<VmSshKey> resolveCandidates(List<String> candidateFingerprints, Long vmId) {
        List<VmSshKey> resolved = new ArrayList<>();
        if (candidateFingerprints == null) {
            return resolved;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String fingerprint : candidateFingerprints) {
            if (fingerprint == null || fingerprint.isBlank() || !seen.add(fingerprint)) {
                continue;
            }
            sshKeyRepository.findByFingerprintSha256(fingerprint)
                    .filter(key -> vmId == null || vmId.equals(key.getVmId()))
                    .ifPresent(resolved::add);
        }
        return resolved;
    }

    private void bumpLastUsed(List<VmSshKey> keys) {
        for (VmSshKey key : keys) {
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

    private static List<String> fingerprintsOf(List<VmSshKey> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (VmSshKey key : keys) {
            out.add(key.getFingerprintSha256());
        }
        return out;
    }

    private static List<Long> keyIdsOf(List<VmSshKey> keys) {
        List<Long> out = new ArrayList<>(keys.size());
        for (VmSshKey key : keys) {
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

    /**
     * The VM's public identifier for the audit trail. The gateway contract
     * itself stays on the internal id (a Go client decodes it as int64), so
     * the translation happens here, at the audit boundary.
     */
    private UUID vmPublicId(Long vmId) {
        return vmId == null ? null
                : vmRepository.findById(vmId).map(Vm::getPublicId).orElse(null);
    }
}
