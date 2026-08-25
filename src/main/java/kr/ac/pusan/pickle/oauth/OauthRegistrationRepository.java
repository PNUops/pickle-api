package kr.ac.pusan.pickle.oauth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OauthRegistrationRepository extends JpaRepository<OauthRegistration, Long> {

    Optional<OauthRegistration> findByTokenHash(String tokenHash);

    /** Single-use consumption; 0 means already spent or expired, which is 410. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OauthRegistration r
               set r.consumedAt = :now
             where r.id = :id and r.consumedAt is null and r.expiresAt > :now
            """)
    int consume(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("delete from OauthRegistration r where r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
