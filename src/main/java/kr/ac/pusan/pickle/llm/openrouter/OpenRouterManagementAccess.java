package kr.ac.pusan.pickle.llm.openrouter;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Decrypted only for one vendor call sequence; {@link #toString()} is redacted. */
public final class OpenRouterManagementAccess {

    private final String scopeKey;
    private final @Nullable Long accountId;
    private final @Nullable UUID accountPublicId;
    private final @Nullable UUID workspaceId;
    private final @Nullable String identityKeyHash;
    private final String secret;
    private final @Nullable Long credentialId;

    OpenRouterManagementAccess(String scopeKey, @Nullable Long accountId,
            @Nullable UUID accountPublicId, @Nullable UUID workspaceId,
            @Nullable String identityKeyHash, String secret,
            @Nullable Long credentialId) {
        this.scopeKey = scopeKey;
        this.accountId = accountId;
        this.accountPublicId = accountPublicId;
        this.workspaceId = workspaceId;
        this.identityKeyHash = identityKeyHash;
        this.secret = secret;
        this.credentialId = credentialId;
    }

    public String scopeKey() { return scopeKey; }
    public @Nullable Long accountId() { return accountId; }
    public @Nullable UUID accountPublicId() { return accountPublicId; }
    public @Nullable UUID workspaceId() { return workspaceId; }
    public @Nullable String identityKeyHash() { return identityKeyHash; }
    String secret() { return secret; }
    public @Nullable Long credentialId() { return credentialId; }

    @Override
    public String toString() {
        return "OpenRouterManagementAccess[scope=" + scopeKey + ", credential=redacted]";
    }
}
