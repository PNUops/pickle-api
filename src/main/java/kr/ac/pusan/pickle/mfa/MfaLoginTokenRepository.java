package kr.ac.pusan.pickle.mfa;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MfaLoginTokenRepository extends JpaRepository<MfaLoginToken, Long> {

    Optional<MfaLoginToken> findByTokenHash(String tokenHash);

    /** Housekeeping: drop expired/consumed step-up tokens (also freed by the retention sweeper). */
    @Modifying
    @Query("delete from MfaLoginToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
