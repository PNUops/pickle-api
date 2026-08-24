package kr.ac.pusan.pickle.llm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LlmApiKeyRepository extends JpaRepository<LlmApiKey, Long> {

    Optional<LlmApiKey> findByPublicId(UUID publicId);

    Page<LlmApiKey> findByWorkspaceId(long workspaceId, Pageable pageable);

    Page<LlmApiKey> findByWorkspaceIdIn(List<Long> workspaceIds, Pageable pageable);

    List<LlmApiKey> findByWorkspaceId(long workspaceId);

    /**
     * Keys that still count as the workspace holding something. A revoked key
     * keeps its row so its usage stays readable, but it holds nothing.
     */
    long countByWorkspaceIdAndStatusNot(long workspaceId, LlmApiKeyStatus status);

    /**
     * Funded keys whose OpenRouter half does not exist yet — the provisioning
     * sweep's worklist (the partial index from V88 serves exactly this shape).
     * Revoked and expired keys are excluded: their money axis is over.
     */
    @Query(value = """
            select * from llm_api_keys k
             where k.openrouter_key_hash is null
               and k.credit_limit > 0
               and k.status in ('PENDING'::llm_api_key_status, 'ACTIVE'::llm_api_key_status)
               and (k.expires_at is null or k.expires_at > now())
            """, nativeQuery = true)
    List<LlmApiKey> findAwaitingOpenrouterProvisioning();

    /** Every key that has an OpenRouter half — the reconciler's local side. */
    List<LlmApiKey> findByOpenrouterKeyHashNotNull();
}
