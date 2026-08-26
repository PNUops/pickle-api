package kr.ac.pusan.pickle.llm;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.dto.IssuedLlmKeyResponse;
import kr.ac.pusan.pickle.llm.dto.UpdateLlmKeyRequest;
import kr.ac.pusan.pickle.llm.openrouter.LlmOpenRouterProvisioner;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The writes on an LLM API key: minting its secret, replacing it, revoking it,
 * and changing what it is called or whether it records bodies.
 *
 * <p>Every one of them bumps the document generation <b>before</b> it writes,
 * in the same transaction. That order is the whole mechanism: the bump takes a
 * row lock until commit, so commit order and generation order agree. Skip it
 * and the change simply never reaches the gateway — the poll that would have
 * carried it already answered "you are current", and nothing bumps again.
 */
@Service
public class LlmApiKeyService {

    private final LlmApiKeyRepository keyRepository;
    private final LlmGatewayGenerations generations;
    private final ResourceAccessResolver resourceAccessResolver;
    private final AuditService auditService;
    private final LlmOpenRouterProvisioner provisioner;

    public LlmApiKeyService(LlmApiKeyRepository keyRepository, LlmGatewayGenerations generations,
            ResourceAccessResolver resourceAccessResolver, AuditService auditService,
            LlmOpenRouterProvisioner provisioner) {
        this.keyRepository = keyRepository;
        this.generations = generations;
        this.resourceAccessResolver = resourceAccessResolver;
        this.auditService = auditService;
        this.provisioner = provisioner;
    }

    /**
     * Mints this key's secret and returns the plaintext — the only time it
     * exists outside the caller's screen.
     *
     * <p>Issuing again is how a key is rotated, and it is the same operation:
     * the old hash is gone when this returns, so the old value stops working
     * at the gateway's next poll. Nothing here can hand back a value that was
     * issued earlier; that is the point of storing only the hash.
     */
    @Transactional
    public IssuedLlmKeyResponse issue(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = writable(actor, keyId, ResourceRole.OWNER);
        if (key.getStatus() == LlmApiKeyStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LLM_KEY_REVOKED,
                    "폐기된 키입니다", "폐기된 키는 다시 발급할 수 없습니다. 새로 신청해 주세요.");
        }
        boolean rotation = key.isIssued();
        String token = LlmApiKeyTokens.newToken();
        generations.bump();
        // Re-read after the lock. The status check above ran against a snapshot
        // taken before this transaction serialized on the counter, so a revoke
        // that committed in between would otherwise be overwritten here — the
        // key would come back ACTIVE with a fresh secret and no revoked_at,
        // which is the one outcome "폐기된 키는 다시 발급할 수 없습니다" promises
        // cannot happen.
        if (keyRepository.findById(key.getId())
                .map(LlmApiKey::getStatus)
                .orElse(LlmApiKeyStatus.REVOKED) == LlmApiKeyStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LLM_KEY_REVOKED,
                    "폐기된 키입니다", "폐기된 키는 다시 발급할 수 없습니다. 새로 신청해 주세요.");
        }
        key.issue(LlmApiKeyTokens.hash(token), LlmApiKeyTokens.visiblePrefix(token), Instant.now());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rotation", rotation);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.LLM_KEY_ISSUE,
                "llm_key", key.getPublicId(), args, null);
        return IssuedLlmKeyResponse.of(key, token);
    }

    /**
     * Revokes the key, keeping its row. The row outlives the secret so the
     * usage it produced stays readable and so the gateway can answer "this key
     * was revoked" rather than "no such key" — different sentences, and only
     * one of them sends a student looking for a typo.
     */
    @Transactional
    public void revoke(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = revocable(actor, keyId);
        if (key.getStatus() == LlmApiKeyStatus.REVOKED) {
            return; // idempotent: a retried click must not move revoked_at
        }
        generations.bump();
        key.revoke(Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.LLM_KEY_REVOKE,
                "llm_key", key.getPublicId(), Map.of(), null);
        // The money axis must die with the key, and it must not wait for the
        // gateway's next poll: OpenRouter refusing directly is what closes the
        // propagation window on a credential with money behind it. After
        // commit so a rolled-back revoke never deletes a live key; failures
        // are the reconciler's to catch.
        String openrouterKeyHash = key.getOpenrouterKeyHash();
        if (openrouterKeyHash != null) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            provisioner.deleteAfterRevoke(openrouterKeyHash);
                        }
                    });
        }
    }

    /**
     * The key, if this caller may revoke it.
     *
     * <p>Revoking is a standing right, not a granted one: a workspace owner may
     * always take a resource of their workspace away, and a platform or
     * organisation administrator may too. Requiring a grant here would mean
     * that during a leaked-key incident the only people who can stop it are the
     * ones already on its access list — and a workspace owner whose key owner
     * has left would have to grant themselves content access first, recording a
     * break-glass entry for what the model already gives them.
     */
    private LlmApiKey revocable(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        if (actor.role() == UserRole.SYS_ADMIN) {
            return key;
        }
        if (actor.role() == UserRole.ORG_ADMIN) {
            if (!actor.administers(key.getOrgId())) {
                throw LlmKeyResourceAdapter.MESSAGES.notFound();
            }
            return key;
        }
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        if (!standing.manages()) {
            standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);
            throw LlmKeyResourceAdapter.MESSAGES.noGrant();
        }
        return key;
    }

    /** Renames the key or turns body recording on or off. */
    @Transactional
    public void update(AuthenticatedUser actor, UUID keyId, UpdateLlmKeyRequest form) {
        LlmApiKey key = writable(actor, keyId, ResourceRole.EDITOR);
        Map<String, Object> args = new LinkedHashMap<>();
        // The name never reaches the gateway, but the recording flag does, so
        // the bump is unconditional rather than conditional on which field
        // moved: a rule with an exception is the rule somebody later copies
        // the exception from.
        generations.bump();
        // Each member is independent: absent means "leave it alone", which is
        // what the contract promises. Folding purpose into the rename would
        // erase a purpose whenever somebody renamed without resending it.
        if (form.name() != null) {
            key.rename(form.name().trim(), Instant.now());
            args.put("name", key.getName());
        }
        if (form.purpose() != null) {
            // Blank is how a purpose is cleared; absent is how it is kept.
            key.setPurpose(Texts.blankToNull(form.purpose()), Instant.now());
            args.put("purpose", key.getPurpose());
        }
        if (form.recordBodies() != null) {
            key.setRecordBodies(form.recordBodies(), Instant.now());
            args.put("recordBodies", form.recordBodies());
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.LLM_KEY_UPDATE,
                "llm_key", key.getPublicId(), args, null);
    }

    /**
     * The key, if this caller may change it at the given rung. A caller who
     * may not see it at all gets the masking 404, never a 403 that confirms
     * the key exists.
     */
    private LlmApiKey writable(AuthenticatedUser actor, UUID keyId, ResourceRole minimum) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);
        if (!standing.atLeast(minimum)) {
            throw LlmKeyResourceAdapter.MESSAGES.noGrant();
        }
        return key;
    }
}
