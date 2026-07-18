package kr.ac.pusan.pickle.mfa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    /** Unused codes for a user — the candidate set a recovery-code login checks against. */
    List<MfaRecoveryCode> findByUserIdAndUsedAtIsNull(long userId);

    @Modifying
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    void deleteByUserId(long userId);
}
