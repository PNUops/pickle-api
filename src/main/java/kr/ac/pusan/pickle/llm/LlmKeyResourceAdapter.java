package kr.ac.pusan.pickle.llm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.llm.dto.LlmKeySummaryResponse;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** What the resource-generic machinery needs to know about an LLM API key. */
@Component
public class LlmKeyResourceAdapter implements ResourceTypeAdapter {

    /** Every sentence the access machinery says about a key. */
    public static final ResourceAccessMessages MESSAGES = new ResourceAccessMessages(
            "해당 LLM API 키가 존재하지 않습니다.",
            new ResourceAccessMessages.Refusal("이 키에 접근할 권한이 없습니다",
                    "이 LLM API 키의 접근 목록에 등록되어 있지 않습니다. 자원 소유자에게 접근 권한을 요청해 주세요."),
            new ResourceAccessMessages.Refusal("접근 권한을 관리할 권한이 없습니다",
                    "이 LLM API 키의 소유자 또는 워크스페이스 소유자만 접근 권한을 관리할 수 있습니다."),
            "이 키를 소유한 워크스페이스의 구성원만 접근 권한을 받을 수 있습니다. 먼저 워크스페이스에 추가해 주세요.",
            ErrorCodes.LLM_KEY_ACCESS_GRANT_EXISTS,
            new ResourceAccessMessages.Refusal("이미 접근 권한이 있습니다",
                    "이 대상은 이미 이 LLM API 키의 접근 목록에 있습니다. 등급을 바꾸려면 기존 항목을 수정해 주세요."));

    private static final ResourceAccessAudit AUDIT = new ResourceAccessAudit("llm_key",
            AuditService.LLM_KEY_ACCESS_GRANT_ADD, AuditService.LLM_KEY_ACCESS_GRANT_UPDATE,
            AuditService.LLM_KEY_ACCESS_GRANT_REMOVE, AuditService.LLM_KEY_ACCESS_BREAK_GLASS);

    private final LlmApiKeyRepository keyRepository;
    private final LlmApiKeyQueryService queryService;

    public LlmKeyResourceAdapter(LlmApiKeyRepository keyRepository,
            LlmApiKeyQueryService queryService) {
        this.keyRepository = keyRepository;
        this.queryService = queryService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.LLM_API_KEY;
    }

    @Override
    public Optional<ResourceIdentity> identify(long resourceId) {
        // No filter on status: a revoked key keeps its row so the people who
        // used it can still read what it did, and the access list is what
        // decides who they are.
        return keyRepository.findById(resourceId).map(LlmKeyResourceAdapter::identityOf);
    }

    @Override
    public Optional<ResourceIdentity> identifyByPublicId(UUID publicId) {
        return keyRepository.findByPublicId(publicId).map(LlmKeyResourceAdapter::identityOf);
    }

    @Override
    public ResourceAccessMessages accessMessages() {
        return MESSAGES;
    }

    @Override
    public ResourceAccessAudit accessAudit() {
        return AUDIT;
    }

    @Override
    public List<Long> idsOwnedByWorkspace(long workspaceId) {
        return keyRepository.findByWorkspaceId(workspaceId).stream().map(LlmApiKey::getId).toList();
    }

    @Override
    public long countLiveInWorkspace(long workspaceId) {
        // A revoked key holds nothing; anything else does, including one that
        // has been approved but not yet minted — the right to it still exists.
        return keyRepository.countByWorkspaceIdAndStatusNot(workspaceId, LlmApiKeyStatus.REVOKED);
    }

    @Override
    public InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit) {
        // Reuses the key list rather than re-deriving visibility: a restricted
        // row must say the same thing in both places, and there is only one
        // place that decides what it says.
        //
        // The sort keys are named here and nowhere else — the inventory asks for
        // "newest first, ties in this type's own order" and this is where that
        // becomes property names of this entity.
        var page = queryService.listPage(actor, workspaceId,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
        return new InventoryHead(
                page.getContent().stream().map(LlmKeyResourceAdapter::toSummary).toList(),
                page.getTotalElements());
    }

    private static ResourceIdentity identityOf(LlmApiKey key) {
        return new ResourceIdentity(key.getId(), key.getPublicId(), key.getWorkspaceId(),
                key.getName(), null, key.getStatus().name());
    }

    private static ResourceSummaryResponse toSummary(LlmKeySummaryResponse key) {
        return new ResourceSummaryResponse(key.id(), ResourceType.LLM_API_KEY, key.name(), null,
                key.status().name(), key.workspaceId(), key.workspaceName(), key.accessLimited(),
                key.ownerNames(), key.accessManageAllowed(), key.createdAt());
    }
}
