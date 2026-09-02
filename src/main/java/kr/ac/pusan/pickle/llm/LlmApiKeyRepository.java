package kr.ac.pusan.pickle.llm;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmApiKeyRepository extends JpaRepository<LlmApiKey, Long>,
        JpaSpecificationExecutor<LlmApiKey> {

    Optional<LlmApiKey> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from LlmApiKey k where k.id = :id")
    Optional<LlmApiKey> findWithLockById(@Param("id") long id);

    Page<LlmApiKey> findByWorkspaceId(long workspaceId, Pageable pageable);

    Page<LlmApiKey> findByWorkspaceIdIn(List<Long> workspaceIds, Pageable pageable);

    List<LlmApiKey> findByWorkspaceId(long workspaceId);

    @Query("""
            select k from LlmApiKey k
             where k.workspaceId in :workspaceIds
               and k.status in :statuses
               and (k.expiresAt is null or k.expiresAt > :now)
             order by k.id desc
            """)
    List<LlmApiKey> findCurrentByWorkspaceIdIn(
            @Param("workspaceIds") List<Long> workspaceIds,
            @Param("statuses") List<LlmApiKeyStatus> statuses,
            @Param("now") Instant now);

    /**
     * Keys that still count as the workspace holding something. A revoked key
     * keeps its row so its usage stays readable, but it holds nothing.
     */
    long countByWorkspaceIdAndStatusNot(long workspaceId, LlmApiKeyStatus status);

    /**
     * Funded keys whose OpenRouter half does not exist yet and whose backoff
     * has elapsed — the provisioning sweep's worklist (the partial index from
     * V88 serves this shape; the added predicate only narrows it further).
     * Revoked and expired keys are excluded: their money axis is over.
     *
     * <p>The backoff term is what stops a vendor refusal from being re-sent
     * at full batch size every five minutes. A key that has never failed has
     * a null timestamp and is picked up immediately, so the ordinary case is
     * unchanged.
     */
    @Query(value = """
            select * from llm_api_keys k
             where k.openrouter_key_hash is null
               and k.credit_limit > 0
               and k.status in ('PENDING'::llm_api_key_status, 'ACTIVE'::llm_api_key_status)
               and (k.expires_at is null or k.expires_at > now())
               and (k.openrouter_not_before_at is null or k.openrouter_not_before_at <= now())
            """, nativeQuery = true)
    List<LlmApiKey> findAwaitingOpenrouterProvisioning();

    /** Every key that has an OpenRouter half — the reconciler's local side. */
    List<LlmApiKey> findByOpenrouterKeyHashNotNull();

    List<LlmApiKey> findByOpenrouterAccountId(long accountId);

    long countByOpenrouterAccountId(long accountId);

    @Query(value = """
            select count(*) from llm_api_keys k
             where k.openrouter_account_id = :accountId
               and k.status in ('PENDING'::llm_api_key_status,
                                'ACTIVE'::llm_api_key_status,
                                'SUSPENDED'::llm_api_key_status)
               and (k.expires_at is null or k.expires_at > now())
            """, nativeQuery = true)
    long countUnsafeForAccountArchive(@Param("accountId") long accountId);
}
