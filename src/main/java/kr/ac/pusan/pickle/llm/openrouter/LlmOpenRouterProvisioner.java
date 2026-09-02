package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.LlmGatewayGenerations;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provisions one OpenRouter runtime key per funded pickle key, by sweep.
 *
 * <p>A sweep rather than an approval-time call, deliberately: the operator
 * decided (2026-08-24) that OpenRouter being down must not block key issuance
 * — the key lands usable on the token axis and the money axis connects when
 * it can. A sweep gives that retry for free, covers a limit granted later to
 * an existing key by the same mechanism, and keeps the external HTTP call out
 * of the approval transaction.</p>
 *
 * <p>The create call runs OUTSIDE any DB transaction (it is slow and remote);
 * only the short write that lands the hash and ciphertext is transactional,
 * bumping the gateway generation first like every document-affecting write.
 * If that write fails after a successful create, the OpenRouter side holds a
 * key we never recorded — the reconciler reports it as an orphan, which is
 * the recoverable direction (a recorded key that was never created would be
 * a credential the document promises and cannot deliver).</p>
 */
@Service
public class LlmOpenRouterProvisioner {

    private static final Logger log = LoggerFactory.getLogger(LlmOpenRouterProvisioner.class);
    private static final int POST_COMMIT_CONVERGENCE_ATTEMPTS = 3;

    static final String JOB_ID = "llm-openrouter-provisioner";

    private final LlmApiKeyRepository keyRepository;
    private final OpenRouterClient client;
    private final CredentialCipher credentialCipher;
    private final OpenRouterCredentialResolver credentialResolver;
    private final LlmGatewayGenerations generations;
    private final TransactionTemplate tx;

    public LlmOpenRouterProvisioner(LlmApiKeyRepository keyRepository, OpenRouterClient client,
            CredentialCipher credentialCipher, LlmGatewayGenerations generations,
            PlatformTransactionManager transactionManager,
            OpenRouterCredentialResolver credentialResolver) {
        this.keyRepository = keyRepository;
        this.client = client;
        this.credentialCipher = credentialCipher;
        this.generations = generations;
        this.credentialResolver = credentialResolver;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Every 5 minutes: the money axis connects within one sweep of being
     * granted, and a failed attempt is retried on the next one.
     */
    @Recurring(id = JOB_ID, cron = "*/5 * * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        List<LlmApiKey> waiting = keyRepository.findAwaitingOpenrouterProvisioning();
        for (LlmApiKey key : waiting) {
            provision(key.getId());
        }
    }

    /**
     * One key, one attempt. The remote create runs outside any transaction
     * (deliberately — see the class comment) and the writes run in their own
     * programmatic transaction, so one key's failure cannot roll back
     * another's success and a slow remote call never holds a DB lock. The
     * transactions are programmatic because the sweep calls this on the same
     * bean, where an annotation would be silently skipped.
     */
    public void provision(long keyId) {
        LlmApiKey key = keyRepository.findById(keyId).orElse(null);
        ProvisioningIntent intent = key == null ? null
                : ProvisioningIntent.capture(key, Instant.now());
        if (intent == null || !intent.initiallyEligible(key)) {
            return; // settled between the scan and now
        }
        OpenRouterManagementAccess access = accessForKey(key);
        if (access == null) {
            tx.executeWithoutResult(status -> keyRepository.findById(keyId)
                    .ifPresent(fresh -> fresh.recordOpenrouterFailure(
                            "ACCOUNT_CREDENTIAL_UNAVAILABLE", Instant.now())));
            return;
        }
        try {
            OpenRouterClient.CreatedKey created = client.createKey(access.secret(),
                    access.workspaceId(), key.getPublicId().toString(), intent.creditLimit(),
                    intent.creditLimitReset(), intent.expiresAt());
            if (access.workspaceId() != null
                    && !access.workspaceId().equals(created.workspaceId())) {
                try {
                    client.deleteKey(access.secret(), created.workspaceId(), created.hash());
                } catch (RuntimeException ignored) { }
                throw new OpenRouterException(0, "created key belongs to a different workspace");
            }
            String enc = credentialCipher.encrypt(created.plaintext());
            boolean[] stranded = {false};
            tx.executeWithoutResult(status -> {
                // Bump FIRST, then read. The counter is the serialization
                // point, so a read taken before it sees a snapshot from
                // before this transaction got in line — and a revoke that
                // commits in that gap would be silently overwritten by the
                // full-column flush below, bringing the key back ACTIVE with
                // a live money credential and no drift finding to show for
                // it. LlmApiKeyService.issue() guards the same hazard the
                // same way; this is not a place to be clever.
                generations.bump();
                LlmApiKey fresh = keyRepository.findWithLockById(keyId).orElse(null);
                if (fresh == null || !intent.stillMatches(fresh, Instant.now())) {
                    // Settled while the remote create was in flight — another
                    // writer got there first, its serving state or expiry
                    // changed, its grant changed, or its management source
                    // moved. Recording the hash now would attach a credential
                    // created from stale policy or the wrong vendor account.
                    stranded[0] = true;
                    status.setRollbackOnly();
                    return;
                }
                fresh.recordOpenrouterKey(created.hash(), enc, Instant.now());
            });
            if (stranded[0]) {
                // Nothing recorded it, so nothing else will ever clean it up:
                // delete it now, and leave the reconciler as the backstop if
                // this call fails too.
                log.warn("the llm key settled while its OpenRouter key was being created; "
                        + "deleting the stranded remote key");
                deleteWithAccess(access, created.hash());
                return;
            }
            credentialResolver.markUsed(access, Instant.now());
            log.info("provisioned an OpenRouter key for llm key {}", key.getPublicId());
        } catch (OpenRouterException e) {
            tx.executeWithoutResult(status -> keyRepository.findById(keyId)
                    .ifPresent(fresh -> fresh.recordOpenrouterFailure(
                            vendorError(e),
                            Instant.now())));
            log.warn("OpenRouter provisioning failed for llm key {}: {}",
                    key.getPublicId(), vendorError(e));
        }
    }

    /**
     * Best-effort deletion of a revoked key's OpenRouter half, called after
     * the revoking transaction commits. Revocation must not wait for the
     * snapshot to propagate — OpenRouter refusing directly is the only thing
     * that closes the poll-lag window on a credential with money behind it.
     * A failure here is logged and left to the reconciler, which reports the
     * still-alive key as drift and disables it.
     */
    public void deleteAfterRevoke(long keyId, String ignoredHash) {
        LlmApiKey key = keyRepository.findById(keyId).orElse(null);
        if (key == null) {
            return;
        }
        String currentHash = key.getOpenrouterKeyHash();
        if (currentHash == null) {
            return;
        }
        OpenRouterManagementAccess access = accessForKey(key);
        if (access == null) {
            return;
        }
        try {
            client.deleteKey(access.secret(), access.workspaceId(), currentHash);
            credentialResolver.markUsed(access, Instant.now());
        } catch (OpenRouterException e) {
            log.warn("OpenRouter key deletion failed; the reconciler will catch it: {}",
                    vendorError(e));
        }
    }

    /** Best-effort post-commit propagation of a changed money ceiling. */
    public void updateLimitAfterChange(long keyId, String ignoredHash, BigDecimal ignoredLimit,
            @Nullable CreditLimitReset ignoredReset) {
        for (int attempt = 1; attempt <= POST_COMMIT_CONVERGENCE_ATTEMPTS; attempt++) {
            LlmApiKey key = keyRepository.findById(keyId).orElse(null);
            if (key == null || key.getOpenrouterKeyHash() == null) { return; }
            LimitPush sent = LimitPush.capture(key);
            OpenRouterManagementAccess access = accessForKey(key);
            if (access == null) { return; }
            try {
                client.updateLimit(access.secret(), access.workspaceId(), sent.hash(),
                        sent.limit(), sent.reset());
                credentialResolver.markUsed(access, Instant.now());
            } catch (OpenRouterException e) {
                log.warn("OpenRouter limit update failed; the reconciler will catch it: {}",
                        vendorError(e));
                return;
            }
            if (sent.matches(keyRepository.findById(keyId).orElse(null))) {
                return;
            }
        }
        log.warn("OpenRouter limit update did not converge after {} attempt(s); "
                + "the reconciler will repair it", POST_COMMIT_CONVERGENCE_ATTEMPTS);
    }

    /** Best-effort post-commit propagation of suspend or resume. */
    public void setDisabledAfterStatusChange(long keyId, String ignoredHash,
            boolean ignoredDisabled) {
        for (int attempt = 1; attempt <= POST_COMMIT_CONVERGENCE_ATTEMPTS; attempt++) {
            LlmApiKey key = keyRepository.findById(keyId).orElse(null);
            if (key == null || key.getOpenrouterKeyHash() == null) { return; }
            StatusPush sent = StatusPush.capture(key, Instant.now());
            OpenRouterManagementAccess access = accessForKey(key);
            if (access == null) { return; }
            try {
                client.setDisabled(access.secret(), access.workspaceId(), sent.hash(),
                        sent.disabled());
                credentialResolver.markUsed(access, Instant.now());
            } catch (OpenRouterException e) {
                log.warn("OpenRouter status update failed; the reconciler will catch it: {}",
                        vendorError(e));
                return;
            }
            LlmApiKey latest = keyRepository.findById(keyId).orElse(null);
            if (sent.matches(latest, Instant.now())) {
                return;
            }
        }
        log.warn("OpenRouter status update did not converge after {} attempt(s); "
                + "the reconciler will repair it", POST_COMMIT_CONVERGENCE_ATTEMPTS);
    }

    private void deleteWithAccess(OpenRouterManagementAccess access, String hash) {
        try {
            client.deleteKey(access.secret(), access.workspaceId(), hash);
        } catch (OpenRouterException e) {
            log.warn("stranded OpenRouter key cleanup failed: {}", vendorError(e));
        }
    }

    private @Nullable OpenRouterManagementAccess accessForKey(LlmApiKey key) {
        try {
            return credentialResolver.forKey(key).orElse(null);
        } catch (OpenRouterException e) {
            log.warn("OpenRouter management credential is unavailable for llm key {}",
                    key.getPublicId());
            return null;
        }
    }

    private static String vendorError(OpenRouterException e) {
        if (e.status() == 401 || e.status() == 403) { return "CREDENTIAL_ERROR"; }
        if (e.status() == 429) { return "THROTTLED"; }
        if (e.status() == 0 || e.status() >= 500) { return "VENDOR_UNAVAILABLE"; }
        return "VENDOR_REJECTED";
    }

    private record ProvisioningIntent(BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset, @Nullable Instant expiresAt,
            LlmApiKeyStatus effectiveStatus, @Nullable Long accountId) {

        private static ProvisioningIntent capture(LlmApiKey key, Instant now) {
            return new ProvisioningIntent(key.getCreditLimit(), key.getCreditLimitReset(),
                    key.getExpiresAt(), key.effectiveStatus(now),
                    key.getOpenrouterAccountId());
        }

        private boolean initiallyEligible(LlmApiKey key) {
            return key.getOpenrouterKeyHash() == null && creditLimit.signum() > 0
                    && allowed(effectiveStatus);
        }

        private boolean stillMatches(LlmApiKey key, Instant now) {
            return key.getOpenrouterKeyHash() == null
                    && key.getCreditLimit().signum() > 0
                    && key.getCreditLimit().compareTo(creditLimit) == 0
                    && Objects.equals(key.getCreditLimitReset(), creditLimitReset)
                    && Objects.equals(key.getExpiresAt(), expiresAt)
                    && allowed(key.effectiveStatus(now))
                    && Objects.equals(key.getOpenrouterAccountId(), accountId);
        }

        private static boolean allowed(LlmApiKeyStatus status) {
            return status == LlmApiKeyStatus.PENDING || status == LlmApiKeyStatus.ACTIVE;
        }
    }

    private record LimitPush(String hash, BigDecimal limit,
            @Nullable CreditLimitReset reset, @Nullable Long accountId) {

        private static LimitPush capture(LlmApiKey key) {
            return new LimitPush(key.getOpenrouterKeyHash(), key.getCreditLimit(),
                    key.getCreditLimitReset(), key.getOpenrouterAccountId());
        }

        private boolean matches(@Nullable LlmApiKey key) {
            return key != null && Objects.equals(key.getOpenrouterKeyHash(), hash)
                    && key.getCreditLimit().compareTo(limit) == 0
                    && Objects.equals(key.getCreditLimitReset(), reset)
                    && Objects.equals(key.getOpenrouterAccountId(), accountId);
        }
    }

    private record StatusPush(String hash, boolean disabled, @Nullable Long accountId) {

        private static StatusPush capture(LlmApiKey key, Instant now) {
            return new StatusPush(key.getOpenrouterKeyHash(),
                    key.effectiveStatus(now) != LlmApiKeyStatus.ACTIVE,
                    key.getOpenrouterAccountId());
        }

        private boolean matches(@Nullable LlmApiKey key, Instant now) {
            return key != null && Objects.equals(key.getOpenrouterKeyHash(), hash)
                    && (key.effectiveStatus(now) != LlmApiKeyStatus.ACTIVE) == disabled
                    && Objects.equals(key.getOpenrouterAccountId(), accountId);
        }
    }
}
