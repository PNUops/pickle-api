package kr.ac.pusan.pickle.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/** A user's consent to one {@link TermsVersion} (table {@code user_consents}, V42). */
@Entity
@Table(name = "user_consents")
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "terms_version_id", nullable = false)
    private Long termsVersionId;

    @CreationTimestamp
    @Column(name = "consented_at", nullable = false, updatable = false)
    private Instant consentedAt;

    protected UserConsent() {
    }

    public UserConsent(long userId, long termsVersionId) {
        this.userId = userId;
        this.termsVersionId = termsVersionId;
    }

    public Long getTermsVersionId() {
        return termsVersionId;
    }

    public Instant getConsentedAt() {
        return consentedAt;
    }
}
