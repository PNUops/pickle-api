package kr.ac.pusan.pickle.consent;

import java.util.List;
import kr.ac.pusan.pickle.consent.dto.ConsentView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    boolean existsByUserIdAndTermsVersionId(long userId, long termsVersionId);

    /** Consent history (doc/version/time), newest first — contract {@code GET /me/consents}. */
    @Query("select new kr.ac.pusan.pickle.consent.dto.ConsentView(t.docType, t.version, c.consentedAt) "
            + "from UserConsent c, TermsVersion t "
            + "where c.userId = :userId and c.termsVersionId = t.id "
            + "order by c.consentedAt desc, t.docType asc")
    List<ConsentView> findConsentHistory(@Param("userId") long userId);
}
