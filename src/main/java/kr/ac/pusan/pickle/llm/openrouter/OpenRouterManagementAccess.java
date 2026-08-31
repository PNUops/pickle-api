package kr.ac.pusan.pickle.llm.openrouter;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Decrypted only for one vendor call sequence; {@link #toString()} is redacted. */
public final class OpenRouterManagementAccess {

    private final String scopeKey;
    private final @Nullable Long accountId;
    private final @Nullable UUID accountPublicId;
    private final @Nullable UUID workspaceId;
    private final String secret;
    private final @Nullable Long credentialId;
    private final boolean includesLegacyKeys;

    OpenRouterManagementAccess(String scopeKey, @Nullable Long accountId,
            @Nullable UUID accountPublicId, @Nullable UUID workspaceId, String secret,
            @Nullable Long credentialId, boolean includesLegacyKeys) {
        this.scopeKey = scopeKey;
        this.accountId = accountId;
        this.accountPublicId = accountPublicId;
        this.workspaceId = workspaceId;
        this.secret = secret;
        this.credentialId = credentialId;
        this.includesLegacyKeys = includesLegacyKeys;
    }

    public String scopeKey() { return scopeKey; }
    public @Nullable Long accountId() { return accountId; }
    public @Nullable UUID accountPublicId() { return accountPublicId; }
    public @Nullable UUID workspaceId() { return workspaceId; }
    String secret() { return secret; }
    public @Nullable Long credentialId() { return credentialId; }
    public boolean includesLegacyKeys() { return includesLegacyKeys; }

    @Override
    public String toString() {
        return "OpenRouterManagementAccess[scope=" + scopeKey + ", credential=redacted]";
    }
}
