package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciles the two halves of every money-funded key: what our table says
 * against what OpenRouter's key list says. Drift here is money — an orphan is
 * spend nobody attributes, a zombie is a revoked key still able to spend —
 * so it lands in the same {@code drift_findings} store and admin screen the
 * Proxmox reconciler uses, as its own kinds.
 *
 * <p>Deliberately its own component rather than a branch of
 * {@code DriftReconciler}: that class is Proxmox-shaped end to end, and the
 * per-kind auto-resolve in the shared repository means two producers of one
 * kind would resolve each other's findings — separate kinds, separate
 * producers, no interference.</p>
 *
 * <p>Remediation is deliberately one-way and reversible: a still-enabled
 * OpenRouter key whose pickle side is revoked or expired is <b>disabled</b>
 * (not deleted — deletion is the revoke path's job, and an operator can
 * re-enable if the finding was wrong). Nothing here ever touches a healthy
 * pairing.</p>
 */
@Component
public class OpenRouterReconciler {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterReconciler.class);

    static final String JOB_ID = "llm-openrouter-reconciler";

    private final LlmApiKeyRepository keyRepository;
    private final OpenRouterClient client;
    private final DriftFindingRepository findings;

    public OpenRouterReconciler(LlmApiKeyRepository keyRepository, OpenRouterClient client,
            DriftFindingRepository findings) {
        this.keyRepository = keyRepository;
        this.client = client;
        this.findings = findings;
    }

    /**
     * Whether OpenRouter's ceiling for this key differs from what we granted.
     * Compared by value, not by object: {@code 1} and {@code 1.00} are the
     * same limit. A null remote limit means unlimited over there, which never
     * matches a granted amount.
     */
    private static boolean limitDiverged(LlmApiKey local, OpenRouterClient.ManagedKey managed) {
        if (managed.limit() == null) {
            return true;
        }
        if (managed.limit().compareTo(local.getCreditLimit()) != 0) {
            return true;
        }
        String granted = local.getCreditLimitReset() == null ? null
                : local.getCreditLimitReset().wireValue();
        return !java.util.Objects.equals(granted, managed.limitReset());
    }

    @Recurring(id = JOB_ID, interval = "PT30M")
    @Job(name = JOB_ID, retries = 0)
    public void reconcile() {
        if (!client.configured()) {
            return;
        }
        List<OpenRouterClient.ManagedKey> remote;
        try {
            remote = client.listKeys();
        } catch (OpenRouterException e) {
            // No list, no verdicts: auto-resolving on a failed read would
            // close every open finding for free. Keep them and try later.
            log.warn("OpenRouter key listing failed; keeping existing findings: HTTP {} {}",
                    e.status(), e.getMessage());
            return;
        }
        Map<String, LlmApiKey> localByHash = new HashMap<>();
        for (LlmApiKey key : keyRepository.findByOpenrouterKeyHashNotNull()) {
            localByHash.put(key.getOpenrouterKeyHash(), key);
        }
        Instant now = Instant.now();
        List<String> orphanKeys = new ArrayList<>();
        List<String> staleKeys = new ArrayList<>();

        for (OpenRouterClient.ManagedKey managed : remote) {
            LlmApiKey local = localByHash.remove(managed.hash());
            if (local == null) {
                // OpenRouter holds a key we never recorded: spend on the
                // shared account that no pickle key explains.
                orphanKeys.add(managed.hash());
                findings.observe(DriftFindingKind.OPENROUTER_ORPHAN, null, null, null,
                        "OpenRouter에 우리 기록에 없는 키가 있습니다: " + managed.name(),
                        "{\"hash\":\"%s\",\"name\":\"%s\",\"disabled\":%s}"
                                .formatted(managed.hash(), managed.name(), managed.disabled()),
                        managed.hash(), now);
                continue;
            }
            boolean over = local.getStatus() == LlmApiKeyStatus.REVOKED
                    || (local.getExpiresAt() != null && local.getExpiresAt().isBefore(now));
            if (!over && limitDiverged(local, managed)) {
                // The money limit is enforced THERE, so a limit that drifted
                // from what we granted is a real spend ceiling nobody chose.
                // Push ours back and report it: silent correction would hide
                // whoever edited it in the OpenRouter console.
                // A finding is the record of an intervention, so it says what
                // actually happened rather than what was attempted — an
                // operator reading "다시 적용했습니다" must be able to believe it.
                boolean reapplied = true;
                try {
                    client.updateLimit(managed.hash(), local.getCreditLimit(),
                            local.getCreditLimitReset());
                } catch (OpenRouterException e) {
                    reapplied = false;
                    log.warn("re-applying a diverged money limit failed: HTTP {} {}",
                            e.status(), e.getMessage());
                }
                staleKeys.add(managed.hash());
                findings.observe(DriftFindingKind.OPENROUTER_STALE, null, null, null,
                        (reapplied
                                ? "OpenRouter 금액 한도가 부여값과 달라 다시 적용했습니다: "
                                : "OpenRouter 금액 한도가 부여값과 다른데 다시 적용하지 못했습니다: ")
                                + local.getPublicId(),
                        "{\"hash\":\"%s\",\"llmKeyId\":\"%s\",\"granted\":\"%s\",\"remote\":\"%s\"}"
                                .formatted(managed.hash(), local.getPublicId(),
                                        local.getCreditLimit(), managed.limit()),
                        managed.hash(), now);
                continue;
            }
            if (over && !managed.disabled()) {
                // The pickle side is over but the money side still works.
                // Disable it now (reversible), report it, and leave deletion
                // to a person.
                boolean disabled = true;
                try {
                    client.setDisabled(managed.hash(), true);
                } catch (OpenRouterException e) {
                    disabled = false;
                    log.warn("disabling a stale OpenRouter key failed: HTTP {} {}",
                            e.status(), e.getMessage());
                }
                staleKeys.add(managed.hash());
                findings.observe(DriftFindingKind.OPENROUTER_STALE, null, null, null,
                        (disabled
                                ? "폐기·만료된 키의 OpenRouter 키가 아직 살아 있어 비활성화했습니다: "
                                : "폐기·만료된 키의 OpenRouter 키가 살아 있는데 비활성화하지 못했습니다: ")
                                + local.getPublicId(),
                        "{\"hash\":\"%s\",\"llmKeyId\":\"%s\",\"status\":\"%s\"}"
                                .formatted(managed.hash(), local.getPublicId(),
                                        local.getStatus()),
                        managed.hash(), now);
            }
        }
        // Whatever is left in localByHash exists here and not there: the
        // provisioned key was deleted on the OpenRouter side (console action,
        // or our own revoke-path deletion). For a revoked/expired key that is
        // the expected end state; for a live funded key it means the money
        // axis silently died — the credential we serve no longer works.
        for (LlmApiKey local : localByHash.values()) {
            boolean over = local.getStatus() == LlmApiKeyStatus.REVOKED
                    || (local.getExpiresAt() != null && local.getExpiresAt().isBefore(now));
            if (over) {
                continue;
            }
            staleKeys.add(local.getOpenrouterKeyHash());
            findings.observe(DriftFindingKind.OPENROUTER_STALE, null, null, null,
                    "활성 키의 OpenRouter 키가 저쪽에서 사라졌습니다: " + local.getPublicId(),
                    "{\"hash\":\"%s\",\"llmKeyId\":\"%s\",\"direction\":\"missing_remote\"}"
                            .formatted(local.getOpenrouterKeyHash(), local.getPublicId()),
                    local.getOpenrouterKeyHash(), now);
        }
        findings.autoResolveNotSeen(DriftFindingKind.OPENROUTER_ORPHAN, orphanKeys, now);
        findings.autoResolveNotSeen(DriftFindingKind.OPENROUTER_STALE, staleKeys, now);
        if (!orphanKeys.isEmpty() || !staleKeys.isEmpty()) {
            log.info("OpenRouter reconcile: {} orphan(s), {} stale", orphanKeys.size(),
                    staleKeys.size());
        }
    }
}
