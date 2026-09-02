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
import org.springframework.data.jpa.repository.Modifying;
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
     * every five minutes. A key that has never failed has a null timestamp
     * and is picked up immediately, so the ordinary case is unchanged.
     *
     * <p>{@code batch} bounds how many key creations one sweep can send. The
     * backoff alone does not: it decides when a key returns, not how many
     * return at once, and a backlog that accumulates while the vendor is
     * refusing would otherwise arrive as one burst the moment the wait ends.
     * The oldest wait goes first, and never-attempted keys ahead of both, so
     * a capped sweep still drains in a fair order rather than starving
     * whatever sorts last.
     */
    @Query(value = """
            select * from llm_api_keys k
             where k.openrouter_key_hash is null
               and k.credit_limit > 0
               and k.status in ('PENDING'::llm_api_key_status, 'ACTIVE'::llm_api_key_status)
               and (k.expires_at is null or k.expires_at > now())
               and (k.openrouter_not_before_at is null or k.openrouter_not_before_at <= now())
             order by k.openrouter_not_before_at nulls first, k.id
             limit :batch
            """, nativeQuery = true)
    List<LlmApiKey> findAwaitingOpenrouterProvisioning(@Param("batch") int batch);

    /**
     * Forgets the provisioning backoff on every key funded by one account.
     *
     * <p>Called when that account gets a working management credential. Every
     * key bound to it will have been failing for the same reason and climbing
     * the same ladder, so without this the fix lands and the keys stay out of
     * the sweep for as long as their last wait — up to nine hours after the
     * operator has already done everything asked of them.
     *
     * <p>A bulk statement rather than loaded entities: this is two columns on
     * a set of rows, and reading each key in to write it back would be the
     * one thing that can undo a concurrent limits change.
     */
    @Modifying
    @Query(value = """
            update llm_api_keys
               set openrouter_attempt_count = 0,
                   openrouter_not_before_at = null,
                   updated_at = now()
             where openrouter_account_id = :accountId
               and openrouter_key_hash is null
               and (openrouter_attempt_count <> 0 or openrouter_not_before_at is not null)
            """, nativeQuery = true)
    int clearOpenrouterBackoffForAccount(@Param("accountId") long accountId);

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
