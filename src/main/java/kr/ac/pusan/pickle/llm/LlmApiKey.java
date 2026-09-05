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
import java.time.Duration;
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

    /**
     * JSON array of the model patterns this key may spend money on, normalized
     * by {@link CreditModelPatterns}. Empty means unrestricted, and there is no
     * null: the column carries a not-null default so "no restriction" has one
     * spelling everywhere.
     *
     * <p>It governs the CREDIT axis alone. A TOKEN-axis self-serving model is
     * reachable whatever this says — the list is a money fence, and there is no
     * money on that axis to fence.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credit_allowed_models", nullable = false, columnDefinition = "jsonb")
    private String creditAllowedModels = CreditModelPatterns.EMPTY_JSON;

    /**
     * JSON array of the model patterns this key may <em>not</em> spend money on,
     * same syntax and same normalizer as the list above. Empty means nothing is
     * blocked; where both lists name a model the deny side wins.
     *
     * <p>Unlike the allow list this one survives a money limit of zero. "This
     * key may not call that model" is true at any amount, and stays true if
     * somebody funds the key tomorrow.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credit_denied_models", nullable = false, columnDefinition = "jsonb")
    private String creditDeniedModels = CreditModelPatterns.EMPTY_JSON;

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

    /**
     * The provisioning sweep's period. The backoff spread is measured against
     * it: a wait that differs by less than one period puts two keys on the
     * same sweep, which is not a spread at all.
     */
    private static final Duration SWEEP_PERIOD = Duration.ofMinutes(5);

    /**
     * What a fault on our side waits. Fixed rather than climbing, and short
     * enough that fixing the credential is felt quickly. Mirrors the account
     * polling path, which uses the same half hour for everything that is not
     * the vendor throttling or down.
     */
    private static final Duration LOCAL_FAULT_BACKOFF = Duration.ofMinutes(30);

    /** Consecutive failed provisioning attempts; sizes the wait below. */
    @Column(name = "openrouter_attempt_count", nullable = false)
    private int openrouterAttemptCount;

    /** No provisioning attempt before this; null means eligible now. */
    @Column(name = "openrouter_not_before_at")
    private @Nullable Instant openrouterNotBeforeAt;

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
        this.openrouterAttemptCount = 0;
        this.openrouterNotBeforeAt = null;
        this.updatedAt = when;
    }

    /**
     * A provisioning attempt failed; the key stays usable on the token axis.
     * The wait until the next attempt grows with consecutive failures, so a
     * vendor that is refusing is not asked again on the same five-minute
     * cadence that produced the refusal.
     *
     * <p>{@code vendorRefused} says whether OpenRouter is the one saying no.
     * A local fault waits a fixed half hour instead of climbing a ladder
     * built for a rate limit it never touched.
     */
    public void recordOpenrouterFailure(String error, boolean vendorRefused, Instant when) {
        this.openrouterLastError = error;
        this.openrouterAttemptCount++;
        this.openrouterNotBeforeAt = when.plus(
                openrouterBackoff(openrouterAttemptCount, vendorRefused, id));
        this.updatedAt = when;
    }

    /**
     * Forgets a run of failed provisioning attempts, so the next sweep tries
     * again at once. Called where somebody changed the thing the failure was
     * probably about: a wait sized for a vendor that keeps refusing is the
     * wrong answer to an operator who has just fixed the cause, and before
     * this backoff existed such a key was retried within five minutes.
     */
    public void clearOpenrouterBackoff(Instant when) {
        if (openrouterAttemptCount == 0 && openrouterNotBeforeAt == null) {
            return;
        }
        this.openrouterAttemptCount = 0;
        this.openrouterNotBeforeAt = null;
        this.updatedAt = when;
    }

    /**
     * How long to wait after {@code failures} consecutive failures.
     *
     * <p>Only a vendor that is refusing gets the ladder. A local fault —
     * no usable management credential, a rejected one — never reached
     * OpenRouter at all, so doubling against a rate limit it did not hit
     * would be superstition; those wait a fixed half hour, which is what the
     * account polling path settled on for the same distinction.
     *
     * <p>The ladder doubles from five minutes to a six-hour ceiling, and the
     * spread is added on top of it: the longest a key actually waits is nine
     * hours, not six. That is intended — at the ceiling the vendor has been
     * refusing for most of a day and the spread is what keeps the return from
     * being a batch — but the ceiling and the wait are different numbers and
     * saying "six hours" for both is how the difference gets lost. The
     * spread is deliberately as wide as the wait rather than a token minute
     * or two: the sweep runs on a five-minute cron and every rung is a
     * multiple of it, so a jitter smaller than one period cannot move two
     * keys onto different sweeps. Keys refused together would come back
     * together and re-send the burst that was refused, which is the thing
     * this exists to stop.
     */
    private static Duration openrouterBackoff(int failures, boolean vendorRefused,
            @Nullable Long keyId) {
        if (!vendorRefused) {
            return LOCAL_FAULT_BACKOFF;
        }
        // The exponent cap has to let the doubling reach past the ceiling,
        // or the ceiling is decoration: from a five-minute floor, stopping at
        // 6 would top out at 320 and the 360 below would never bind.
        int exponent = Math.min(Math.max(failures, 1) - 1, 7);
        long minutes = Math.min(5L << exponent, 360L);
        // Half the wait, and never less than two sweep periods. One period is
        // not enough and reads as though it were: a spread exactly one period
        // wide lands every key in the same bucket, because the sweep fires on
        // wall-clock ticks and the whole spread then fits between two of them.
        // Two periods is the narrowest that can put two keys on different
        // sweeps at the bottom rungs.
        long spreadSeconds = Math.max(2 * SWEEP_PERIOD.toSeconds(), minutes * 30);
        return Duration.ofMinutes(minutes)
                .plusSeconds(Math.floorMod(scatter(keyId, failures), spreadSeconds));
    }

    /**
     * Turns a key id into a spread offset that neighbouring ids do not share.
     *
     * <p>The obvious {@code hashCode} of a small arithmetic combination is
     * near enough to the identity that consecutive ids come out a fixed few
     * seconds apart, which lands them in the same sweep and leaves the batch
     * exactly as clustered as it was. Keys are created in runs and their ids
     * are consecutive, so that is the normal case rather than a corner. This
     * is the SplitMix64 finalizer, whose whole job is that a step of one in
     * means an unrelated value out.
     */
    private static long scatter(@Nullable Long keyId, int failures) {
        long z = (keyId == null ? 0L : keyId) * 0x9E3779B97F4A7C15L + failures;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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

    /**
     * Replaces every independently managed limit in one write.
     *
     * <p>The last two arguments are the allowed list and then the denied list,
     * both JSON arrays of the same shape, so nothing but their order tells them
     * apart at a call site and transposing them inverts the fence. The
     * administrator replacement test asserts both stored columns against
     * different values for exactly that reason.
     */
    public void replaceLimits(@Nullable Integer rpm, @Nullable Integer tpm,
            @Nullable Integer concurrency, @Nullable Long dailyTokens,
            BigDecimal creditLimit, @Nullable CreditLimitReset creditLimitReset,
            String creditAllowedModels, String creditDeniedModels, Instant when) {
        this.rpm = rpm;
        this.tpm = tpm;
        this.concurrency = concurrency;
        this.dailyTokens = dailyTokens;
        this.creditLimit = creditLimit;
        this.creditLimitReset = creditLimitReset;
        this.creditAllowedModels = creditAllowedModels;
        this.creditDeniedModels = creditDeniedModels;
        this.updatedAt = when;
    }

    /**
     * Sets the money-axis model lists at approval.
     *
     * <p>Kept out of the constructor deliberately: that signature already
     * carries fourteen positional arguments, and further Strings beside
     * {@code name} and {@code purpose} are the shape a caller transposes without
     * the compiler noticing.
     */
    public void applyCreditModelLists(String creditAllowedModels, String creditDeniedModels) {
        this.creditAllowedModels = creditAllowedModels;
        this.creditDeniedModels = creditDeniedModels;
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

    /** The stored JSON array; read it with {@link CreditModelPatterns#fromJson}. */
    public String getCreditAllowedModels() {
        return creditAllowedModels;
    }

    /** The stored JSON array; read it with {@link CreditModelPatterns#fromJson}. */
    public String getCreditDeniedModels() {
        return creditDeniedModels;
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
