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
import kr.ac.pusan.pickle.identity.IdentityProvider;
import org.jspecify.annotations.Nullable;

/**
 * A verified external identity that has no account yet (V89).
 *
 * <p>The account is not created at the callback on purpose. Signup wraps user
 * creation and consent recording in one transaction precisely so an incomplete
 * consent set rolls the user back and no half-account survives; creating the
 * row here and collecting consent afterwards would break that on the social
 * path and leave an ACTIVE account that has agreed to nothing. This token is
 * what carries the verified identity across to the onboarding form instead.
 */
@Entity
@Table(name = "oauth_registrations")
public class OauthRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityProvider provider;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column
    private @Nullable String name;

    @Column(name = "hosted_domain")
    private @Nullable String hostedDomain;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private @Nullable Instant consumedAt;

    protected OauthRegistration() {
    }

    public OauthRegistration(String tokenHash, IdentityProvider provider, String subject, String email,
            @Nullable String name, @Nullable String hostedDomain, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.name = name;
        this.hostedDomain = hostedDomain;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public @Nullable String getName() {
        return name;
    }

    public @Nullable String getHostedDomain() {
        return hostedDomain;
    }
}
