package kr.ac.pusan.pickle.mfa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    /** Unused codes for a user — the candidate set a recovery-code login checks against. */
    List<MfaRecoveryCode> findByUserIdAndUsedAtIsNull(long userId);

    /**
     * Single-use consumption guard: the conditional UPDATE wins for exactly one
     * concurrent caller (mirrors {@code EmailVerificationRepository.consume}), so
     * a recovery code cannot be spent twice by parallel logins. Returns 0 when it
     * was already used.
     */
    @Modifying
    @Query("update MfaRecoveryCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int consume(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    void deleteByUserId(long userId);
}
