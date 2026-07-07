package kr.ac.pusan.pickle.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByTokenHashAndPurpose(String tokenHash, VerificationPurpose purpose);

    /**
     * Single-use consumption guard: the conditional UPDATE wins for exactly
     * one concurrent caller (mirrors the refresh-token rotation pattern).
     * Returns 0 when the token was already consumed — callers must treat
     * that as 410.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update EmailVerification ev
               set ev.usedAt = :now, ev.updatedAt = :now
             where ev.id = :id and ev.usedAt is null
            """)
    int consume(@Param("id") Long id, @Param("now") Instant now);
}
