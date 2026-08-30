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
import org.jspecify.annotations.Nullable;
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
    private final OpenRouterSpendRecorder spendRecorder;

    public OpenRouterReconciler(LlmApiKeyRepository keyRepository, OpenRouterClient client,
            DriftFindingRepository findings, OpenRouterSpendRecorder spendRecorder) {
        this.keyRepository = keyRepository;
        this.client = client;
        this.findings = findings;
        this.spendRecorder = spendRecorder;
    }

    /**
     * How OpenRouter's ceiling for this key differs from what we granted, or
     * null when it does not. Amounts are compared by value, not by object:
     * {@code 1} and {@code 1.00} are the same limit. A null remote limit means
     * unlimited over there, which never matches a granted amount.
     *
     * <p>A key whose {@code include_byok_in_limit} is false counts as diverged
     * even when the amount matches, because the amount is then not being
     * enforced against BYOK inference at all. That is the more dangerous shape
     * of the two: a wrong ceiling is visible, a ceiling that counts nothing
     * looks exactly like a correct one.
     *
     * <p>The reason is returned rather than a boolean because it is shown to
     * an operator. Four different states reach the same repair, and a finding
     * that says the amount differs when the amounts are identical reads as the
     * reconciler malfunctioning.
     */
    private static @Nullable String limitDivergence(
            LlmApiKey local, OpenRouterClient.ManagedKey managed) {
        if (managed.limit() == null) {
            return "상한이 걸려 있지 않음";
        }
        if (!managed.includeByokInLimit()) {
            return "BYOK 사용분을 한도에서 세지 않음";
        }
        if (managed.limit().compareTo(local.getCreditLimit()) != 0) {
            return "금액이 부여값과 다름";
        }
        String granted = local.getCreditLimitReset() == null ? null
                : local.getCreditLimitReset().wireValue();
        if (!java.util.Objects.equals(granted, managed.limitReset())) {
            return "리셋 주기가 부여값과 다름";
        }
        return null;
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
        List<OpenRouterSpendRecorder.Spend> spends = new ArrayList<>();

        for (OpenRouterClient.ManagedKey managed : remote) {
            LlmApiKey local = localByHash.remove(managed.hash());
            if (local != null && managed.usage() != null) {
                // Collected on every matched key, whatever verdict follows: a
                // key whose limit drifted has still spent what it spent, and
                // the money figure is what the console shows instead of the
                // OpenRouter console.
                spends.add(new OpenRouterSpendRecorder.Spend(local.getId(), managed.usage(),
                        local.getCreditLimit()));
            }
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
            String divergence = over ? null : limitDivergence(local, managed);
            if (divergence != null) {
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
                                ? "OpenRouter 금액 한도를 다시 적용했습니다(%s): %s"
                                : "OpenRouter 금액 한도를 다시 적용하지 못했습니다(%s): %s")
                                .formatted(divergence, local.getPublicId()),
                        ("{\"hash\":\"%s\",\"llmKeyId\":\"%s\",\"reason\":\"%s\","
                                + "\"granted\":\"%s\",\"remote\":\"%s\","
                                + "\"includeByokInLimit\":%s}")
                                .formatted(managed.hash(), local.getPublicId(), divergence,
                                        local.getCreditLimit(), managed.limit(),
                                        managed.includeByokInLimit()),
                        managed.hash(), now);
                continue;
            }
            boolean shouldBeDisabled = over || local.getStatus() == LlmApiKeyStatus.SUSPENDED;
            boolean statusDiverged = shouldBeDisabled != managed.disabled()
                    && (shouldBeDisabled || local.getStatus() == LlmApiKeyStatus.ACTIVE);
            if (statusDiverged) {
                // Suspend/resume is authoritative here too. A failed direct
                // post-commit propagation is repaired by this pass, while a
                // revoked or expired remote credential remains fail-closed.
                boolean repaired = true;
                try {
                    client.setDisabled(managed.hash(), shouldBeDisabled);
                } catch (OpenRouterException e) {
                    repaired = false;
                    log.warn("repairing a stale OpenRouter key status failed: HTTP {} {}",
                            e.status(), e.getMessage());
                }
                staleKeys.add(managed.hash());
                String statusSummary;
                if (over) {
                    statusSummary = repaired
                            ? "폐기·만료된 키의 OpenRouter 키가 아직 살아 있어 비활성화했습니다: "
                            : "폐기·만료된 키의 OpenRouter 키가 살아 있는데 비활성화하지 못했습니다: ";
                } else if (shouldBeDisabled) {
                    statusSummary = repaired
                            ? "정지된 키의 OpenRouter 키를 비활성화했습니다: "
                            : "정지된 키의 OpenRouter 키를 비활성화하지 못했습니다: ";
                } else {
                    statusSummary = repaired
                            ? "활성 키의 OpenRouter 키를 다시 활성화했습니다: "
                            : "활성 키의 OpenRouter 키를 다시 활성화하지 못했습니다: ";
                }
                findings.observe(DriftFindingKind.OPENROUTER_STALE, null, null, null,
                        statusSummary + local.getPublicId(),
                        ("{\"hash\":\"%s\",\"llmKeyId\":\"%s\",\"status\":\"%s\","
                                + "\"expectedDisabled\":%s}")
                                .formatted(managed.hash(), local.getPublicId(),
                                        local.getStatus(), shouldBeDisabled),
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
        // Last, after every verdict is settled: spend reporting is the least
        // important thing this job does, and a failure here must not cost a
        // finding or leave one open that the listing just resolved.
        spendRecorder.record(spends, now);
        if (!orphanKeys.isEmpty() || !staleKeys.isEmpty()) {
            log.info("OpenRouter reconcile: {} orphan(s), {} stale", orphanKeys.size(),
                    staleKeys.size());
        }
    }
}
