package kr.ac.pusan.pickle.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One issued LLM API key.
 *
 * <p>The plaintext is shown once at issue and stored nowhere: what lives here
 * is its sha256, which is also what the gateway computes from the token a
 * student presents. A lost key is reissued, never recovered — the requirements
 * say so, and it is the only claim this table can honestly make.
 *
 * <p>Owned by a workspace like every other resource, and reachable through the
 * same access list, so who may use it is decided by grants rather than by a
 * column here.
 */
@Entity
@Table(name = "llm_api_keys")
public class LlmApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private String name;

    @Column
    private @Nullable String purpose;

    /**
     * Null until the owner issues; see {@link LlmApiKeyStatus#PENDING}.
     *
     * <p>The column is {@code char(64)}, and Hibernate validates the schema at
     * startup: without the explicit CHAR mapping it expects varchar, the
     * mismatch fails validation, and the whole application context refuses to
     * build. {@code Relay.tokenHash} carries the same pair for the same reason.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = 64)
    private @Nullable String tokenHash;

    @Column(name = "token_prefix")
    private @Nullable String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private LlmApiKeyStatus status = LlmApiKeyStatus.PENDING;

    @Column(name = "expires_at")
    private @Nullable Instant expiresAt;

    /** Stamped from the usage the gateway ships, so it lags by a batch. */
    @Column(name = "last_used_at")
    private @Nullable Instant lastUsedAt;

    @Column
    private @Nullable Integer rpm;

    @Column
    private @Nullable Integer tpm;

    @Column
    private @Nullable Integer concurrency;

    /**
     * The daily token allowance an approval granted, null for none. Enforced
     * by the api, not the gateway: the gateway observes one request at a time
     * and has no place to keep a day's running total, while the api receives
     * every usage event anyway.
     */
    @Column(name = "daily_tokens")
    private @Nullable Long dailyTokens;

    /**
     * Whether today's usage has reached {@link #dailyTokens}. Derived, but
     * stored: the gateway is only handed a new document when the generation
     * moves, and the generation moves on writes. A flag computed at read time
     * would be correct in the database and invisible to the gateway forever.
     */
    @Column(name = "quota_exhausted", nullable = false)
    private boolean quotaExhausted;

    @Column(name = "record_bodies", nullable = false)
    private boolean recordBodies;

    /**
     * The money-axis limit in USD credits. Never null: 0 means the commercial
     * axis is unusable — deliberately unlike {@link #dailyTokens}, whose null
     * means unlimited. Money has no unlimited; every usable state is a number
     * a reviewer granted.
     */
    @Column(name = "credit_limit", nullable = false)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    /** How the money limit renews on OpenRouter; null = total cap (default). */
    @Column(name = "credit_limit_reset")
    @Enumerated(EnumType.STRING)
    private @Nullable CreditLimitReset creditLimitReset;

    /** OpenRouter's identifier for this key's own OpenRouter key. */
    @Column(name = "openrouter_key_hash")
    private @Nullable String openrouterKeyHash;

    /** CredentialCipher ciphertext of that key's runtime secret. */
    @Column(name = "openrouter_key_enc")
    private @Nullable String openrouterKeyEnc;

    @Column(name = "openrouter_provisioned_at")
    private @Nullable Instant openrouterProvisionedAt;

    @Column(name = "openrouter_last_error")
    private @Nullable String openrouterLastError;

    /** Immutable vendor-account binding; null only for a token-only row. */
    @Column(name = "openrouter_account_id")
    private @Nullable Long openrouterAccountId;

    /**
     * What OpenRouter last reported this key had spent, and when that was read.
     * Written by the reconciliation rather than by anything on a request path,
     * so it is always as stale as that job's interval — which is why the time
     * travels with it. Null is unreported, never zero.
     */
    @Column(name = "openrouter_usage")
    private @Nullable BigDecimal openrouterUsage;

    @Column(name = "openrouter_usage_at")
    private @Nullable Instant openrouterUsageAt;

    @Column(name = "openrouter_limit_remaining")
    private @Nullable BigDecimal openrouterLimitRemaining;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    protected LlmApiKey() {
    }

    /** A key an approval created. The secret is minted later, by its owner. */
    public LlmApiKey(long workspaceId, long orgId, long requestId, String name,
            @Nullable String purpose, @Nullable Instant expiresAt, @Nullable Integer rpm,
            @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens, @Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset, long createdBy) {
        this(workspaceId, orgId, requestId, name, purpose, expiresAt, rpm, tpm, concurrency,
                dailyTokens, creditLimit, creditLimitReset, null, createdBy);
    }

    public LlmApiKey(long workspaceId, long orgId, long requestId, String name,
            @Nullable String purpose, @Nullable Instant expiresAt, @Nullable Integer rpm,
            @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens, @Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset,
            @Nullable Long openrouterAccountId, long createdBy) {
        this.publicId = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.orgId = orgId;
        this.requestId = requestId;
        this.name = name;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.rpm = rpm;
        this.tpm = tpm;
        this.concurrency = concurrency;
        this.dailyTokens = dailyTokens;
        this.creditLimit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
        this.creditLimitReset = creditLimitReset;
        this.openrouterAccountId = openrouterAccountId;
        this.createdBy = createdBy;
    }

    /**
     * Records the OpenRouter key provisioned for this key. Clearing the error
     * here is what lets the provisioning sweep read "hash present, error null"
     * as done.
     */
    public void recordOpenrouterKey(String keyHash, String keyEnc, Instant when) {
        this.openrouterKeyHash = keyHash;
        this.openrouterKeyEnc = keyEnc;
        this.openrouterProvisionedAt = when;
        this.openrouterLastError = null;
        this.updatedAt = when;
    }

    /** A provisioning attempt failed; the key stays usable on the token axis. */
    public void recordOpenrouterFailure(String error, Instant when) {
        this.openrouterLastError = error;
        this.updatedAt = when;
    }

    /**
     * Whether the commercial axis actually works for this key right now: a
     * positive limit granted and an OpenRouter key provisioned. The sync
     * document includes the credential exactly when this is true (and the key
     * is ACTIVE), so this is also what the console shows as 연결 상태.
     */
    public boolean isCreditAxisConnected() {
        return creditLimit.signum() > 0 && openrouterKeyHash != null;
    }

    /** Status shown to a reader at {@code now}; expiry wins without a write. */
    public LlmApiKeyStatus effectiveStatus(Instant now) {
        if (status != LlmApiKeyStatus.REVOKED && expiresAt != null && !expiresAt.isAfter(now)) {
            return LlmApiKeyStatus.EXPIRED;
        }
        return status;
    }

    /**
     * Marks the key revoked, keeping the row. Idempotent: revoking twice is
     * what a retried click looks like, and the second one must not move the
     * timestamp that says when access actually ended.
     */
    public void revoke(Instant when) {
        if (status == LlmApiKeyStatus.REVOKED) {
            return;
        }
        this.status = LlmApiKeyStatus.REVOKED;
        this.revokedAt = when;
        this.updatedAt = when;
    }

    /**
     * Mints or replaces the secret. Rotation is the same operation as the first
     * issue: the old hash is gone the moment this returns, which is what makes
     * "the old value stops working immediately" true rather than aspirational.
     */
    public void issue(String tokenHash, String tokenPrefix, Instant when) {
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        if (status == LlmApiKeyStatus.PENDING) {
            this.status = LlmApiKeyStatus.ACTIVE;
        }
        this.updatedAt = when;
    }

    /** Whether a secret exists for this key at all. */
    public boolean isIssued() {
        return tokenHash != null;
    }

    public void rename(String name, Instant when) {
        this.name = name;
        this.updatedAt = when;
    }

    public void setPurpose(@Nullable String purpose, Instant when) {
        this.purpose = purpose;
        this.updatedAt = when;
    }

    public void setRecordBodies(boolean recordBodies, Instant when) {
        this.recordBodies = recordBodies;
        this.updatedAt = when;
    }

    /** Replaces every independently managed limit in one write. */
    public void replaceLimits(@Nullable Integer rpm, @Nullable Integer tpm,
            @Nullable Integer concurrency, @Nullable Long dailyTokens,
            BigDecimal creditLimit, @Nullable CreditLimitReset creditLimitReset,
            Instant when) {
        this.rpm = rpm;
        this.tpm = tpm;
        this.concurrency = concurrency;
        this.dailyTokens = dailyTokens;
        this.creditLimit = creditLimit;
        this.creditLimitReset = creditLimitReset;
        this.updatedAt = when;
    }

    /** First binding only. A bound key never moves or clears its vendor account. */
    public void bindOpenrouterAccount(long accountId, Instant when) {
        if (openrouterAccountId != null && openrouterAccountId != accountId) {
            throw new IllegalStateException("OpenRouter account binding is immutable");
        }
        // An unbound row that already holds a vendor key was provisioned
        // somewhere else; binding it now would leave that spend behind under
        // a management scope this account cannot see. The same rule is a
        // trigger on llm_api_keys, and this is its entity-side twin.
        if (openrouterAccountId == null
                && (openrouterKeyHash != null || openrouterKeyEnc != null)) {
            throw new IllegalStateException("a provisioned OpenRouter key cannot be bound");
        }
        openrouterAccountId = accountId;
        updatedAt = when;
    }

    public void suspend(Instant when) {
        this.status = LlmApiKeyStatus.SUSPENDED;
        this.updatedAt = when;
    }

    public void resume(Instant when) {
        this.status = LlmApiKeyStatus.ACTIVE;
        this.updatedAt = when;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getPurpose() {
        return purpose;
    }

    public @Nullable String getTokenPrefix() {
        return tokenPrefix;
    }

    public LlmApiKeyStatus getStatus() {
        return status;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public @Nullable Integer getRpm() {
        return rpm;
    }

    public @Nullable Integer getTpm() {
        return tpm;
    }

    public @Nullable Integer getConcurrency() {
        return concurrency;
    }

    public @Nullable Long getDailyTokens() {
        return dailyTokens;
    }

    public boolean isQuotaExhausted() {
        return quotaExhausted;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public @Nullable CreditLimitReset getCreditLimitReset() {
        return creditLimitReset;
    }

    public @Nullable String getOpenrouterKeyHash() {
        return openrouterKeyHash;
    }

    public @Nullable String getOpenrouterKeyEnc() {
        return openrouterKeyEnc;
    }

    public @Nullable Instant getOpenrouterProvisionedAt() {
        return openrouterProvisionedAt;
    }

    public @Nullable String getOpenrouterLastError() {
        return openrouterLastError;
    }

    public @Nullable BigDecimal getOpenrouterUsage() {
        return openrouterUsage;
    }

    public @Nullable Instant getOpenrouterUsageAt() {
        return openrouterUsageAt;
    }

    public @Nullable BigDecimal getOpenrouterLimitRemaining() {
        return openrouterLimitRemaining;
    }

    public @Nullable Long getOpenrouterAccountId() { return openrouterAccountId; }

    public boolean isRecordBodies() {
        return recordBodies;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }
}
