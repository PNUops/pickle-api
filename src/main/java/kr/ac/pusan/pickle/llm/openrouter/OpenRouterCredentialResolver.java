package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves exactly one management source; account-bound rows never use env fallback. */
@Service
public class OpenRouterCredentialResolver {

    private final OpenRouterAccountRepository accountRepository;
    private final OpenRouterAccountCredentialRepository credentialRepository;
    private final OpenRouterManagementCredentialCipher cipher;
    private final OpenRouterProperties legacyProperties;

    public OpenRouterCredentialResolver(OpenRouterAccountRepository accountRepository,
            OpenRouterAccountCredentialRepository credentialRepository,
            OpenRouterManagementCredentialCipher cipher, OpenRouterProperties legacyProperties) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.cipher = cipher;
        this.legacyProperties = legacyProperties;
    }

    @Transactional(readOnly = true)
    public Optional<OpenRouterManagementAccess> forKey(LlmApiKey key) {
        if (key.getOpenrouterAccountId() != null) {
            OpenRouterAccount account = accountRepository.findById(key.getOpenrouterAccountId())
                    .orElse(null);
            return account == null ? Optional.empty() : databaseAccess(account, false);
        }
        if (!key.isOpenrouterLegacy()) {
            return Optional.empty();
        }
        return legacyProperties.configured()
                ? Optional.of(envAccess()) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public ReconciliationAccesses reconciliationScopes() {
        List<OpenRouterManagementAccess> result = new ArrayList<>();
        boolean complete = true;
        for (OpenRouterAccountCredential credential : credentialRepository
                .findByStatus(OpenRouterCredentialStatus.ACTIVE)) {
            OpenRouterAccount account = accountRepository.findById(credential.getAccountId())
                    .orElse(null);
            if (account == null || credential.getVerifiedAt() == null) {
                continue;
            }
            try {
                result.add(decrypt(account, credential, false));
            } catch (OpenRouterException e) {
                complete = false;
            }
        }
        if (legacyProperties.configured()) {
            result.add(envAccess());
        }
        return new ReconciliationAccesses(List.copyOf(result), complete);
    }

    @Transactional
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

    private Optional<OpenRouterManagementAccess> databaseAccess(OpenRouterAccount account,
            boolean includeLegacy) {
        return credentialRepository.findByAccountIdAndStatus(account.getId(),
                        OpenRouterCredentialStatus.ACTIVE)
                .filter(credential -> credential.getVerifiedAt() != null)
                .map(credential -> decrypt(account, credential, includeLegacy));
    }

    private OpenRouterManagementAccess decrypt(OpenRouterAccount account,
            OpenRouterAccountCredential credential, boolean includeLegacy) {
        try {
            String secret = cipher.decrypt(account.getPublicId(), credential.getCredentialEnc());
            return new OpenRouterManagementAccess(account.getPublicId().toString(), account.getId(),
                    account.getPublicId(), account.getVendorWorkspaceId(), secret,
                    credential.getId(), includeLegacy);
        } catch (IllegalStateException e) {
            throw new OpenRouterException(0, "management credential is unavailable");
        }
    }

    private OpenRouterManagementAccess envAccess() {
        return new OpenRouterManagementAccess("legacy-env", null, null, null,
                legacyProperties.managementKey(), null, true);
    }

    public record ReconciliationAccesses(List<OpenRouterManagementAccess> scopes,
            boolean complete) {
    }
}
