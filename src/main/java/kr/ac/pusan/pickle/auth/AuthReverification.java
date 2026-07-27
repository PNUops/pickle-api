package kr.ac.pusan.pickle.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Sudo-mode reauthentication token (auth_reverifications, V59): multi-use for
 * its 10-minute TTL — one prompt covers a whole sensitive workflow. Only the
 * SHA-256 of the raw token is stored; the pinned token_version makes every
 * global-invalidation event (password change/reset, withdrawal, disable, role
 * change) revoke outstanding tokens for free.
 */
@Entity
@Table(name = "auth_reverifications")
public class AuthReverification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Audit only — validity is never bound to the caller's address. */
    @Column(name = "created_ip")
    private String createdIp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthReverification() {
    }

    public AuthReverification(long userId, String tokenHash, int tokenVersion, Instant expiresAt,
            String createdIp) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.tokenVersion = tokenVersion;
        this.expiresAt = expiresAt;
        this.createdIp = createdIp;
    }

    public Long getUserId() {
        return userId;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
