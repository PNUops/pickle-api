package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the one management credential an account owns; there is no fallback source. */
@Service
public class OpenRouterCredentialResolver {

    private final OpenRouterAccountRepository accountRepository;
    private final OpenRouterAccountCredentialRepository credentialRepository;
    private final OpenRouterManagementCredentialCipher cipher;

    public OpenRouterCredentialResolver(OpenRouterAccountRepository accountRepository,
            OpenRouterAccountCredentialRepository credentialRepository,
            OpenRouterManagementCredentialCipher cipher) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public Optional<OpenRouterManagementAccess> forKey(LlmApiKey key) {
        if (key.getOpenrouterAccountId() == null) {
            return Optional.empty();
        }
        return accountRepository.findById(key.getOpenrouterAccountId())
                .flatMap(this::databaseAccess);
    }

    @Transactional(readOnly = true)
    public Optional<OpenRouterManagementAccess> forAccount(UUID accountPublicId) {
        return accountRepository.findByPublicId(accountPublicId)
                .flatMap(this::databaseAccess);
    }

    /**
     * Bookkeeping only, and the callers make the propagation matter: the
     * provisioner touches this from after-commit callbacks, where the
     * caller's transaction is finished but still bound. Joining it there
     * fails the write and throws back out of the commit hook, so this one
     * always runs in a transaction of its own. The column write is monotonic,
     * so committing separately loses nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUsed(OpenRouterManagementAccess access, Instant when) {
        if (access.credentialId() != null) {
            credentialRepository.touchLastUsed(access.credentialId(), when);
        }
    }

    @Transactional
    public void markReconciled(OpenRouterManagementAccess access, Instant when) {
        if (access.credentialId() != null) {
            credentialRepository.recordReconcileSuccess(access.credentialId(), when);
        }
    }

    @Transactional
    public void markVerificationFailure(OpenRouterManagementAccess access,
            OpenRouterCredentialError error, Instant when) {
        if (access.credentialId() != null) {
            credentialRepository.recordActiveVerificationFailure(
                    access.credentialId(), error.name(), when);
        }
    }

    private Optional<OpenRouterManagementAccess> databaseAccess(OpenRouterAccount account) {
        return credentialRepository.findByAccountIdAndStatus(account.getId(),
                        OpenRouterCredentialStatus.ACTIVE)
                .filter(credential -> credential.getVerifiedAt() != null)
                .map(credential -> decrypt(account, credential));
    }

    private OpenRouterManagementAccess decrypt(OpenRouterAccount account,
            OpenRouterAccountCredential credential) {
        try {
            String secret = cipher.decrypt(account.getPublicId(), credential.getCredentialEnc());
            return new OpenRouterManagementAccess(account.getPublicId().toString(), account.getId(),
                    account.getPublicId(), account.getVendorWorkspaceId(),
                    account.getVendorIdentityKeyHash(), secret,
                    credential.getId());
        } catch (IllegalStateException e) {
            throw new OpenRouterException(0, "management credential is unavailable");
        }
    }
}
