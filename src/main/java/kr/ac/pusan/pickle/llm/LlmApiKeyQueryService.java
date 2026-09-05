package kr.ac.pusan.pickle.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeySummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only LLM API key views (contract tag {@code llm-keys}). Visibility is
 * the platform's, not the type's: members of the owning workspace see that a
 * key exists; only a grant opens the row; a non-member is answered as if the
 * key did not exist. The contract defines no 403 for the list, so a
 * workspaceId filter outside my workspaces yields an empty page.
 *
 * <p>What a restricted row must hide is sharper here than for a VM: nothing
 * that authenticates or helps guess may leave. The token hash is absent from
 * every view by construction — no DTO carries a field for it — and the token
 * prefix, safe only as a label for people already inside, is dropped from the
 * restricted row.
 */
@Service
public class LlmApiKeyQueryService {

    private final LlmApiKeyRepository keyRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final ObjectMapper objectMapper;

    public LlmApiKeyQueryService(LlmApiKeyRepository keyRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository,
            ResourceAccessResolver resourceAccessResolver, ObjectMapper objectMapper) {
        this.keyRepository = keyRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<LlmKeySummaryResponse> list(AuthenticatedUser actor, UUID workspaceId,
            int page, int size) {
        Page<LlmKeySummaryResponse> result = listPage(actor, workspaceId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(result.getContent(), result);
    }

    /**
     * The same list, as a {@link Page} — the shape the resource inventory
     * reuses so that one set of visibility rules serves both surfaces, the way
     * {@code VmQueryService.listPage} serves the VM's adapter.
     */
    @Transactional(readOnly = true)
    public Page<LlmKeySummaryResponse> listPage(AuthenticatedUser actor, UUID workspaceId,
            Pageable pageable) {
        List<WorkspaceMember> memberships =
                workspaceMemberRepository.findWithWorkspaceByUserId(actor.id());
        Map<Long, Workspace> workspaces = memberships.stream()
                .collect(Collectors.toMap(m -> m.getWorkspace().getId(),
                        WorkspaceMember::getWorkspace, (first, second) -> first));
        List<Long> workspaceIds = List.copyOf(workspaces.keySet());
        Page<LlmApiKey> result;
        if (workspaceId != null) {
            // An unknown workspace id and one outside my memberships answer the
            // same empty page: the contract defines no 403 for the list.
            Long filterId = workspaceRepository.findByPublicId(workspaceId)
                    .map(Workspace::getId).orElse(null);
            result = filterId != null && workspaceIds.contains(filterId)
                    ? keyRepository.findByWorkspaceId(filterId, pageable)
                    : Page.empty(pageable);
        } else {
            result = workspaceIds.isEmpty()
                    ? Page.empty(pageable)
                    : keyRepository.findByWorkspaceIdIn(workspaceIds, pageable);
        }
        List<LlmApiKey> keys = result.getContent();
        Set<Long> ownedWorkspaceIds = memberships.stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(m -> m.getWorkspace().getId())
                .collect(Collectors.toSet());
        ResourceAccessResolver.ListAccess access = resourceAccessResolver.listAccess(
                ResourceType.LLM_API_KEY, keys.stream().map(LlmApiKey::getId).toList(),
                actor.id());
        return new PageImpl<>(keys.stream()
                .map(key -> {
                    Workspace workspace = workspaces.get(key.getWorkspaceId());
                    UUID workspacePublicId = workspace == null ? null : workspace.getPublicId();
                    String workspaceName = workspace == null ? "" : workspace.getName();
                    // Only a grant opens the row. A workspace owner without one
                    // gets the same restricted row as anyone else, plus the flag
                    // that lets the console offer them the access list — the way
                    // back in for a key whose own owner is gone.
                    if (access.reachable().contains(key.getId())) {
                        return LlmKeySummaryResponse.from(key, workspacePublicId, workspaceName);
                    }
                    return LlmKeySummaryResponse.restricted(key, workspacePublicId, workspaceName,
                            access.ownerNames().getOrDefault(key.getId(), List.of()),
                            ownedWorkspaceIds.contains(key.getWorkspaceId()));
                })
                .toList(), pageable, result.getTotalElements());
    }

    /**
     * The full detail, for a grant holder. Unknown key and existing-but-masked
     * key both answer 404; a member of the owning workspace who can already see
     * it listed is refused in the open with 403.
     */
    @Transactional(readOnly = true)
    public LlmKeyDetailResponse get(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);
        Workspace workspace = workspaceRepository.findById(key.getWorkspaceId()).orElse(null);
        return LlmKeyDetailResponse.from(key,
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(),
                CreditModelPatterns.fromJson(objectMapper, key.getCreditAllowedModels()),
                standing.role(), standing.manages());
    }
}
