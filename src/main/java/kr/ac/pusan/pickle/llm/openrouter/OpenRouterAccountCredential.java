package kr.ac.pusan.pickle.llm.openrouter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** A write-only management credential in the staged-overlap lifecycle. */
@Entity
@Table(name = "openrouter_account_credentials")
public class OpenRouterAccountCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private OpenRouterCredentialStatus status;

    @Column(name = "credential_enc", nullable = false, updatable = false)
    private String credentialEnc;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_verification_attempt_at")
    private @Nullable Instant lastVerificationAttemptAt;

    @Column(name = "verified_at")
    private @Nullable Instant verifiedAt;

    @Column(name = "activated_at")
    private @Nullable Instant activatedAt;

    @Column(name = "retiring_at")
    private @Nullable Instant retiringAt;

    @Column(name = "last_used_at")
    private @Nullable Instant lastUsedAt;

    @Column(name = "last_reconciled_at")
    private @Nullable Instant lastReconciledAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "verification_error")
    private @Nullable OpenRouterCredentialError verificationError;

    protected OpenRouterAccountCredential() {
    }

    public OpenRouterAccountCredential(long accountId, String credentialEnc, long createdBy,
            Instant verifiedAt) {
        this.accountId = accountId;
        this.credentialEnc = credentialEnc;
        this.createdBy = createdBy;
        this.status = OpenRouterCredentialStatus.STAGED;
        this.lastVerificationAttemptAt = verifiedAt;
        this.verifiedAt = verifiedAt;
        this.lastUsedAt = verifiedAt;
    }

    public void activate(Instant now) {
        status = OpenRouterCredentialStatus.ACTIVE;
        activatedAt = now;
        retiringAt = null;
        verificationError = null;
    }

    public void retire(Instant now) {
        status = OpenRouterCredentialStatus.RETIRING;
        retiringAt = now;
    }

    public void restoreToStaged(Instant now) {
        status = OpenRouterCredentialStatus.STAGED;
        activatedAt = null;
        retiringAt = null;
        lastVerificationAttemptAt = now;
    }

    public void markUsed(Instant now) { lastUsedAt = now; }
    public void markReconciled(Instant now) { lastUsedAt = now; lastReconciledAt = now; }

    public void recordVerificationSuccess(Instant now) {
        lastVerificationAttemptAt = now;
        verifiedAt = now;
        verificationError = null;
        lastUsedAt = now;
    }

    public void recordVerificationFailure(OpenRouterCredentialError error, Instant now) {
        lastVerificationAttemptAt = now;
        verificationError = error;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public OpenRouterCredentialStatus getStatus() { return status; }
    public String getCredentialEnc() { return credentialEnc; }
    public Instant getCreatedAt() { return createdAt; }
    public @Nullable Instant getLastVerificationAttemptAt() { return lastVerificationAttemptAt; }
    public @Nullable Instant getVerifiedAt() { return verifiedAt; }
    public @Nullable Instant getActivatedAt() { return activatedAt; }
    public @Nullable Instant getRetiringAt() { return retiringAt; }
    public @Nullable Instant getLastUsedAt() { return lastUsedAt; }
    public @Nullable Instant getLastReconciledAt() { return lastReconciledAt; }
    public @Nullable OpenRouterCredentialError getVerificationError() { return verificationError; }
}
