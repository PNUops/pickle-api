package kr.ac.pusan.pickle.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One single-use 2FA recovery code (table {@code mfa_recovery_codes}, V40).
 * Stored only as a BCrypt hash; {@code used_at} marks consumption.
 */
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaRecoveryCode() {
    }

    public MfaRecoveryCode(long userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public Long getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed(Instant when) {
        this.usedAt = when;
    }
}
