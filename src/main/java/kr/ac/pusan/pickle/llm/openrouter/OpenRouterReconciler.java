package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reconciles each OpenRouter vendor account as an isolated management scope. */
@Component
public class OpenRouterReconciler {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterReconciler.class);

    private final LlmApiKeyRepository keyRepository;
    private final OpenRouterClient client;
    private final DriftFindingRepository findings;
    private final OpenRouterSpendRecorder spendRecorder;
    private final OpenRouterCredentialResolver credentialResolver;

    public OpenRouterReconciler(LlmApiKeyRepository keyRepository, OpenRouterClient client,
            DriftFindingRepository findings, OpenRouterSpendRecorder spendRecorder,
            OpenRouterCredentialResolver credentialResolver) {
        this.keyRepository = keyRepository;
        this.client = client;
        this.findings = findings;
        this.spendRecorder = spendRecorder;
        this.credentialResolver = credentialResolver;
    }

    /**
     * One account's bounded key observation, used by the durable PAIR worker.
     * Only this successful scope resolves its own drift namespace; another
     * account's failure cannot keep old findings open here.
     */
    public ScopeObservation reconcileAccount(OpenRouterManagementAccess access,
            OpenRouterPollRepository.Claim claim, Instant now, boolean baselineExists,
            Clock clock) {
        if (access.accountId() == null) {
            throw new IllegalArgumentException("account reconciliation requires an account scope");
        }
        ScopeResult result = reconcileScope(access, now);
        Instant observedAt = Instant.now(clock);
        OpenRouterSpendRecorder.AccountRecordResult recorded = spendRecorder.recordAccount(
                result.spends(), observedAt, baselineExists, claim, () -> {
                    recordFindings(result.findingObservations(), observedAt);
                    credentialResolver.markReconciled(access, observedAt);
                    String prefix = "account:" + access.scopeKey() + ":key:";
                    findings.autoResolveNotSeenInScope(DriftFindingKind.OPENROUTER_ORPHAN,
                            prefix, result.orphans(), observedAt);
                    findings.autoResolveNotSeenInScope(DriftFindingKind.OPENROUTER_STALE,
                            prefix, result.stale(), observedAt);
                });
        if (!recorded.persisted()) {
            return new ScopeObservation(result.usageComplete(), false,
                    recorded.resetBoundary() || result.managedBoundary(), observedAt);
        }
        return new ScopeObservation(result.usageComplete(), true,
                recorded.resetBoundary() || result.managedBoundary(), observedAt);
    }

    private ScopeResult reconcileScope(OpenRouterManagementAccess access, Instant now) {
        List<OpenRouterClient.ManagedKey> remote = client.listKeys(
                access.secret(), access.workspaceId());
        if (access.workspaceId() != null && remote.stream().anyMatch(key ->
                !access.workspaceId().equals(key.workspaceId()))) {
            throw new OpenRouterException(0, "key listing crossed a workspace boundary");
        }

        Map<String, LlmApiKey> localByHash = new HashMap<>();
        if (access.accountId() != null) {
            for (LlmApiKey key : keyRepository.findByOpenrouterAccountId(access.accountId())) {
                if (key.getOpenrouterKeyHash() != null) {
                    localByHash.put(key.getOpenrouterKeyHash(), key);
                }
            }
        }

        List<String> orphanKeys = new ArrayList<>();
        List<String> staleKeys = new ArrayList<>();
        List<OpenRouterSpendRecorder.Spend> spends = new ArrayList<>();
        List<FindingObservation> findingObservations = new ArrayList<>();
        boolean usageComplete = true;
        boolean managedBoundary = false;
        Set<String> seenRemoteHashes = new HashSet<>();
        boolean identitySeen = access.identityKeyHash() == null;
        for (OpenRouterClient.ManagedKey managed : remote) {
            if (!seenRemoteHashes.add(managed.hash())) {
                throw new OpenRouterException(0, "key listing contained a duplicate hash");
            }
            if (managed.hash().equals(access.identityKeyHash())) {
                identitySeen = true;
                continue;
            }
            String dedup = dedup(access, managed.hash());
            LlmApiKey local = localByHash.remove(managed.hash());
            if (local != null && managed.usage() != null) {
                spends.add(new OpenRouterSpendRecorder.Spend(local.getId(), managed.usage(),
                        local.getCreditLimit(), managed.limitRemaining()));
            } else if (local != null) {
                usageComplete = false;
            }
            if (local == null) {
                orphanKeys.add(dedup);
                findingObservations.add(new FindingObservation(
                        DriftFindingKind.OPENROUTER_ORPHAN,
                        "OpenRouter account에 Pickle 기록과 연결되지 않은 key가 있습니다.",
                        "{\"accountId\":\"%s\",\"disabled\":%s}"
                                .formatted(access.scopeKey(), managed.disabled()),
                        dedup));
                continue;
            }

            boolean over = local.getStatus() == LlmApiKeyStatus.REVOKED
                    || (local.getExpiresAt() != null && local.getExpiresAt().isBefore(now));
            String divergence = over ? null : limitDivergence(local, managed);
            boolean limitReapplied = false;
            if (divergence != null) {
                try {
                    client.updateLimit(access.secret(), access.workspaceId(), managed.hash(),
                            local.getCreditLimit(), local.getCreditLimitReset());
                    limitReapplied = true;
                } catch (OpenRouterException e) {
                    log.warn("OpenRouter limit repair failed in scope {}: {}",
                            access.scopeKey(), vendorError(e));
                }
            }
            boolean shouldBeDisabled = over || local.getStatus() == LlmApiKeyStatus.SUSPENDED;
            boolean statusDiverged = shouldBeDisabled != managed.disabled()
                    && (shouldBeDisabled || local.getStatus() == LlmApiKeyStatus.ACTIVE);
            boolean statusRepaired = false;
            if (statusDiverged) {
                try {
                    client.setDisabled(access.secret(), access.workspaceId(), managed.hash(),
                            shouldBeDisabled);
                    statusRepaired = true;
                } catch (OpenRouterException e) {
                    log.warn("OpenRouter status repair failed in scope {}: {}",
                            access.scopeKey(), vendorError(e));
                }
            }
            if (divergence != null || statusDiverged) {
                staleKeys.add(dedup);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("accountId", access.scopeKey());
                detail.put("llmKeyId", local.getPublicId());
                detail.put("limitReason", divergence);
                detail.put("limitReapplied", limitReapplied);
                detail.put("expectedDisabled", shouldBeDisabled);
                detail.put("statusRepaired", statusRepaired);
                findingObservations.add(new FindingObservation(
                        DriftFindingKind.OPENROUTER_STALE,
                        staleSummary(divergence, limitReapplied, statusDiverged,
                                statusRepaired, local.getStatus(), over, shouldBeDisabled),
                        json(detail), dedup));
            }
        }

        if (!identitySeen) {
            throw new OpenRouterException(0,
                    "vendor billing identity marker is missing from the key listing");
        }

        for (LlmApiKey local : localByHash.values()) {
            boolean over = local.getStatus() == LlmApiKeyStatus.REVOKED
                    || (local.getExpiresAt() != null && local.getExpiresAt().isBefore(now));
            if (over) {
                continue;
            }
            managedBoundary = true;
            String dedup = dedup(access, local.getOpenrouterKeyHash());
            staleKeys.add(dedup);
            findingObservations.add(new FindingObservation(
                    DriftFindingKind.OPENROUTER_STALE,
                    "활성 Pickle key의 OpenRouter runtime key가 vendor account에서 사라졌습니다.",
                    "{\"accountId\":\"%s\",\"llmKeyId\":\"%s\",\"direction\":\"missing_remote\"}"
                            .formatted(access.scopeKey(), local.getPublicId()),
                    dedup));
        }
        return new ScopeResult(orphanKeys, staleKeys, spends, findingObservations,
                usageComplete, managedBoundary);
    }

    private void recordFindings(List<FindingObservation> observations, Instant now) {
        for (FindingObservation observation : observations) {
            findings.observe(observation.kind(), null, null, null, observation.summary(),
                    observation.detail(), observation.dedupKey(), now);
        }
    }

    private static @Nullable String limitDivergence(
            LlmApiKey local, OpenRouterClient.ManagedKey managed) {
        if (managed.limit() == null) { return "상한이 걸려 있지 않음"; }
        if (!managed.includeByokInLimit()) { return "BYOK 사용분을 한도에서 세지 않음"; }
        if (managed.limit().compareTo(local.getCreditLimit()) != 0) {
            return "금액이 부여값과 다름";
        }
        String granted = local.getCreditLimitReset() == null ? null
                : local.getCreditLimitReset().wireValue();
        return Objects.equals(granted, managed.limitReset())
                ? null : "리셋 주기가 부여값과 다름";
    }

    private static String staleSummary(@Nullable String divergence, boolean limitReapplied,
            boolean status, boolean statusRepaired, LlmApiKeyStatus localStatus,
            boolean over, boolean shouldBeDisabled) {
        String limit = divergence == null ? null
                : (limitReapplied ? "OpenRouter 금액 한도를 다시 적용했습니다(" :
                        "OpenRouter 금액 한도를 다시 적용하지 못했습니다(")
                        + divergence + ")";
        String statusText = null;
        if (status) {
            if (over) {
                statusText = statusRepaired
                        ? "폐기·만료된 키의 OpenRouter 키가 아직 살아 있어 비활성화했습니다"
                        : "폐기·만료된 키의 OpenRouter 키가 살아 있는데 비활성화하지 못했습니다";
            } else if (shouldBeDisabled || localStatus == LlmApiKeyStatus.SUSPENDED) {
                statusText = statusRepaired
                        ? "정지된 키의 OpenRouter 키를 비활성화했습니다"
                        : "정지된 키의 OpenRouter 키를 비활성화하지 못했습니다";
            } else {
                statusText = statusRepaired
                        ? "활성 키의 OpenRouter 키를 다시 활성화했습니다"
                        : "활성 키의 OpenRouter 키를 다시 활성화하지 못했습니다";
            }
        }
        return limit != null && statusText != null ? limit + "; " + statusText
                : limit != null ? limit : statusText;
    }

    private static String dedup(OpenRouterManagementAccess access, @Nullable String hash) {
        return "account:" + access.scopeKey() + ":key:" + hash;
    }

    private static String vendorError(OpenRouterException e) {
        return OpenRouterErrorClassifier.classify(e).name();
    }

    private static OpenRouterCredentialError credentialError(OpenRouterException e) {
        return OpenRouterErrorClassifier.classify(e);
    }

    /** Small JSON encoder for values generated by this class, never vendor text. */
    private static String json(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        values.forEach((key, value) -> {
            if (out.length() > 1) { out.append(','); }
            out.append('"').append(key).append("\":");
            if (value == null) { out.append("null"); }
            else if (value instanceof Boolean) { out.append(value); }
            else { out.append('"').append(value).append('"'); }
        });
        return out.append('}').toString();
    }

    private record ScopeResult(List<String> orphans, List<String> stale,
            List<OpenRouterSpendRecorder.Spend> spends,
            List<FindingObservation> findingObservations, boolean usageComplete,
            boolean managedBoundary) {
    }

    private record FindingObservation(DriftFindingKind kind, String summary,
            String detail, String dedupKey) {
    }

    public record ScopeObservation(boolean usageComplete, boolean persisted,
            boolean resetBoundary, Instant observedAt) {
    }
}
