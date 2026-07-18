package kr.ac.pusan.pickle.consent;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsVersionRepository extends JpaRepository<TermsVersion, Long> {

    /** The current (highest already-effective) version of a document. */
    Optional<TermsVersion> findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByVersionDesc(
            TermsDocType docType, Instant now);

    Optional<TermsVersion> findByDocTypeAndVersion(TermsDocType docType, int version);
}
