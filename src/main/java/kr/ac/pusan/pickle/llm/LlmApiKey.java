package kr.ac.pusan.pickle.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false)
    private String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private LlmApiKeyStatus status = LlmApiKeyStatus.ACTIVE;

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

    @Column(name = "record_bodies", nullable = false)
    private boolean recordBodies;

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

    public LlmApiKey(long workspaceId, long orgId, long requestId, String name,
            @Nullable String purpose, String tokenHash, String tokenPrefix,
            @Nullable Instant expiresAt, @Nullable Integer rpm, @Nullable Integer tpm,
            @Nullable Integer concurrency, long createdBy) {
        this.publicId = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.orgId = orgId;
        this.requestId = requestId;
        this.name = name;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt = expiresAt;
        this.rpm = rpm;
        this.tpm = tpm;
        this.concurrency = concurrency;
        this.createdBy = createdBy;
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

    public void rename(String name, @Nullable String purpose, Instant when) {
        this.name = name;
        this.purpose = purpose;
        this.updatedAt = when;
    }

    public void setRecordBodies(boolean recordBodies, Instant when) {
        this.recordBodies = recordBodies;
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

    public String getTokenPrefix() {
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
