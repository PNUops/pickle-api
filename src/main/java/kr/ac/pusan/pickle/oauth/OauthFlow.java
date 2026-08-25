package kr.ac.pusan.pickle.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One in-flight authorization-code exchange (V89).
 *
 * <p>The nonce and the PKCE verifier live here rather than in a cookie: the
 * console's session cookies are {@code __Host-} + {@code SameSite=Strict}, and
 * a cookie set before the Google redirect would not come back with the
 * navigation that returns from it. Storing them server-side and matching on the
 * opaque {@code state} the console echoes avoids needing a cookie policy
 * exception for this one flow.
 */
@Entity
@Table(name = "oauth_flows")
public class OauthFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, unique = true)
    private String stateHash;

    @Column(nullable = false)
    private String nonce;

    @Column(name = "code_verifier", nullable = false)
    private String codeVerifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OauthPurpose purpose;

    @Column(name = "initiating_user_id")
    private @Nullable Long initiatingUserId;

    @Column(name = "redirect_to")
    private @Nullable String redirectTo;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private @Nullable Instant consumedAt;

    protected OauthFlow() {
    }

    public OauthFlow(String stateHash, String nonce, String codeVerifier, OauthPurpose purpose,
            @Nullable Long initiatingUserId, @Nullable String redirectTo, Instant expiresAt) {
        this.stateHash = stateHash;
        this.nonce = nonce;
        this.codeVerifier = codeVerifier;
        this.purpose = purpose;
        this.initiatingUserId = initiatingUserId;
        this.redirectTo = redirectTo;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public OauthPurpose getPurpose() {
        return purpose;
    }

    public @Nullable Long getInitiatingUserId() {
        return initiatingUserId;
    }

    public @Nullable String getRedirectTo() {
        return redirectTo;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
