package kr.ac.pusan.pickle.identity;

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
 * A link between a local account and an external identity (V89).
 *
 * <p>The join key is {@link #subject} — the provider's stable subject id — not
 * the address. A Workspace mailbox can be renamed while {@code sub} stays put,
 * so keying on {@code sub} is what lets a rename keep working. {@link
 * #emailAtLink} records the address the link was made with and is never
 * synchronised: {@code users.email} is what invitations, audit and
 * notifications use to name a person, so it does not change underneath them.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityProvider provider;

    @Column(nullable = false)
    private String subject;

    @Column(name = "email_at_link", nullable = false, columnDefinition = "citext")
    private String emailAtLink;

    @Column(name = "hosted_domain")
    private @Nullable String hostedDomain;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt = Instant.now();

    @Column(name = "last_login_at")
    private @Nullable Instant lastLoginAt;

    protected UserIdentity() {
    }

    public UserIdentity(Long userId, IdentityProvider provider, String subject, String emailAtLink,
            @Nullable String hostedDomain, Instant linkedAt) {
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.emailAtLink = emailAtLink;
        this.hostedDomain = hostedDomain;
        this.linkedAt = linkedAt;
        this.lastLoginAt = linkedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public @Nullable String getHostedDomain() {
        return hostedDomain;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public @Nullable Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void markLogin(Instant when) {
        this.lastLoginAt = when;
    }
}
