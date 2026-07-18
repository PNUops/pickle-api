package kr.ac.pusan.pickle.mfa;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaLoginTokenRepository extends JpaRepository<MfaLoginToken, Long> {

    Optional<MfaLoginToken> findByTokenHash(String tokenHash);

    /**
     * Single-use consumption guard: the conditional UPDATE wins for exactly one
     * concurrent caller (mirrors {@code EmailVerificationRepository.consume}).
     * Returns 0 when the token was already consumed — the caller treats that as
     * 410.
     */
    @Modifying
    @Query("update MfaLoginToken t set t.consumedAt = :now where t.id = :id and t.consumedAt is null")
    int consume(@Param("id") Long id, @Param("now") Instant now);

    /** Housekeeping: drop expired/consumed step-up tokens (also freed by the retention sweeper). */
    @Modifying
    @Query("delete from MfaLoginToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
