package kr.ac.pusan.pickle.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Per-user 2FA(TOTP) enrollment state (table {@code user_mfa}, V39). One row per
 * user holds both the active secret and a pending (begun-but-not-activated) one;
 * {@link #isEnrolled()} ({@code enabled_at} present) is the single enrolled
 * predicate. Secrets are AES-256-GCM ciphertext — never the raw Base32.
 */
@Entity
@Table(name = "user_mfa")
public class UserMfa {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "totp_secret_enc")
    private String totpSecretEnc;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "pending_secret_enc")
    private String pendingSecretEnc;

    @Column(name = "pending_created_at")
    private Instant pendingCreatedAt;

    /** Highest TOTP step already consumed; a code at a step ≤ this is a replay. */
    @Column(name = "last_totp_step")
    private Long lastTotpStep;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserMfa() {
    }

    public UserMfa(long userId) {
        this.userId = userId;
    }

    /** Enrolled = a confirmed, active secret exists. */
    public boolean isEnrolled() {
        return enabledAt != null;
    }

    public boolean hasPendingSetup() {
        return pendingSecretEnc != null;
    }

    /** Begin/re-begin: stash a fresh pending secret, replacing any prior one. */
    public void startPending(String encryptedSecret, Instant now) {
        this.pendingSecretEnc = encryptedSecret;
        this.pendingCreatedAt = now;
    }

    /** Activation: promote the pending secret to active and drop the pending slot. */
    public void activate(Instant now) {
        this.totpSecretEnc = this.pendingSecretEnc;
        this.enabledAt = now;
        this.pendingSecretEnc = null;
        this.pendingCreatedAt = null;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTotpSecretEnc() {
        return totpSecretEnc;
    }

    public String getPendingSecretEnc() {
        return pendingSecretEnc;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public Long getLastTotpStep() {
        return lastTotpStep;
    }

    /** Marks {@code step} as consumed so the same code cannot be replayed. */
    public void recordTotpStep(long step) {
        this.lastTotpStep = step;
    }
}
