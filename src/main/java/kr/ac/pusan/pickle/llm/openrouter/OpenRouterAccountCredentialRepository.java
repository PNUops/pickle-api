package kr.ac.pusan.pickle.llm.openrouter;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpenRouterAccountCredentialRepository
        extends JpaRepository<OpenRouterAccountCredential, Long> {

    Optional<OpenRouterAccountCredential> findByAccountIdAndStatus(
            long accountId, OpenRouterCredentialStatus status);

    List<OpenRouterAccountCredential> findByAccountIdOrderByIdAsc(long accountId);

    List<OpenRouterAccountCredential> findByStatus(OpenRouterCredentialStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OpenRouterAccountCredential c where c.accountId = :accountId")
    List<OpenRouterAccountCredential> findAllWithLockByAccountId(@Param("accountId") long accountId);

    @Modifying
    @Query(value = """
            update openrouter_account_credentials
               set last_used_at = greatest(coalesce(last_used_at, :when), :when)
             where id = :id
            """, nativeQuery = true)
    int touchLastUsed(@Param("id") long id, @Param("when") java.time.Instant when);

    @Modifying
    @Query(value = """
            update openrouter_account_credentials
               set last_used_at = greatest(coalesce(last_used_at, :when), :when),
                   last_reconciled_at = greatest(coalesce(last_reconciled_at, :when), :when),
                   last_verification_attempt_at = greatest(
                       coalesce(last_verification_attempt_at, :when), :when),
                   verified_at = greatest(coalesce(verified_at, :when), :when),
                   verification_error = case
                       when last_verification_attempt_at is null
                            or last_verification_attempt_at <= :when then null
                       else verification_error
                   end
             where id = :id
               and status = 'ACTIVE'::openrouter_credential_status
            """, nativeQuery = true)
    int recordReconcileSuccess(@Param("id") long id, @Param("when") java.time.Instant when);

    @Modifying
    @Query(value = """
            update openrouter_account_credentials
               set last_verification_attempt_at = greatest(
                       coalesce(last_verification_attempt_at, :when), :when),
                   verification_error = case
                       when last_verification_attempt_at is null
                            or last_verification_attempt_at <= :when
                           then cast(:error as openrouter_credential_error)
                       else verification_error
                   end
             where id = :id
               and status = 'ACTIVE'::openrouter_credential_status
            """, nativeQuery = true)
    int recordActiveVerificationFailure(@Param("id") long id,
            @Param("error") String error, @Param("when") java.time.Instant when);
}
