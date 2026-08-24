package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.LlmGatewayGenerations;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
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

    static final String JOB_ID = "llm-openrouter-provisioner";

    private final LlmApiKeyRepository keyRepository;
    private final OpenRouterClient client;
    private final CredentialCipher credentialCipher;
    private final LlmGatewayGenerations generations;
    private final TransactionTemplate tx;

    public LlmOpenRouterProvisioner(LlmApiKeyRepository keyRepository, OpenRouterClient client,
            CredentialCipher credentialCipher, LlmGatewayGenerations generations,
            PlatformTransactionManager transactionManager) {
        this.keyRepository = keyRepository;
        this.client = client;
        this.credentialCipher = credentialCipher;
        this.generations = generations;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Every 5 minutes: the money axis connects within one sweep of being
     * granted, and a failed attempt is retried on the next one.
     */
    @Recurring(id = JOB_ID, cron = "*/5 * * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        if (!client.configured()) {
            // No management key yet: every funded key simply stays
            // unconnected, which the console says out loud. Not an error —
            // the operator decided this failure shape.
            return;
        }
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
        if (key == null || key.getOpenrouterKeyHash() != null
                || key.getCreditLimit().signum() <= 0) {
            return; // settled between the scan and now
        }
        try {
            OpenRouterClient.CreatedKey created = client.createKey(
                    key.getPublicId().toString(), key.getCreditLimit(),
                    key.getCreditLimitReset(), key.getExpiresAt());
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
                LlmApiKey fresh = keyRepository.findById(keyId).orElse(null);
                if (fresh == null || fresh.getOpenrouterKeyHash() != null
                        || fresh.getStatus() == LlmApiKeyStatus.REVOKED
                        || fresh.getCreditLimit().signum() <= 0) {
                    // Settled while the remote create was in flight — another
                    // writer got there first, or the key was revoked, or its
                    // money budget was withdrawn. Recording the hash now would
                    // attach a live OpenRouter key to a key that must not have
                    // one, and the revoke path already ran its deletion
                    // against a hash that did not exist yet. So this side
                    // drops it and deletes the remote half below.
                    stranded[0] = true;
                }
                if (!stranded[0]) {
                    fresh.recordOpenrouterKey(created.hash(), enc, Instant.now());
                }
            });
            if (stranded[0]) {
                // Nothing recorded it, so nothing else will ever clean it up:
                // delete it now, and leave the reconciler as the backstop if
                // this call fails too.
                log.warn("the llm key settled while its OpenRouter key was being created; "
                        + "deleting the stranded remote key");
                deleteAfterRevoke(created.hash());
                return;
            }
            log.info("provisioned an OpenRouter key for llm key {}", key.getPublicId());
        } catch (OpenRouterException e) {
            tx.executeWithoutResult(status -> keyRepository.findById(keyId)
                    .ifPresent(fresh -> fresh.recordOpenrouterFailure(
                            "HTTP " + e.status() + ": " + bounded(e.getMessage()),
                            Instant.now())));
            log.warn("OpenRouter provisioning failed for llm key {}: HTTP {} {}",
                    key.getPublicId(), e.status(), e.getMessage());
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
    public void deleteAfterRevoke(String openrouterKeyHash) {
        if (!client.configured()) {
            return;
        }
        try {
            client.deleteKey(openrouterKeyHash);
        } catch (OpenRouterException e) {
            log.warn("OpenRouter key deletion failed (the reconciler will catch it): HTTP {} {}",
                    e.status(), e.getMessage());
        }
    }

    private static String bounded(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
