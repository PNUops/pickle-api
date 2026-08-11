package kr.ac.pusan.pickle.llm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmApiKeyRepository extends JpaRepository<LlmApiKey, Long> {

    Optional<LlmApiKey> findByPublicId(UUID publicId);

    Page<LlmApiKey> findByWorkspaceId(long workspaceId, Pageable pageable);

    List<LlmApiKey> findByWorkspaceId(long workspaceId);

    /**
     * Keys that still count as the workspace holding something. A revoked key
     * keeps its row so its usage stays readable, but it holds nothing.
     */
    long countByWorkspaceIdAndStatusNot(long workspaceId, LlmApiKeyStatus status);
}
