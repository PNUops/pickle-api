package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.publishing.dto.DnsDomainView;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * What the resource-generic machinery needs to know about a name issued on its
 * own.
 *
 * <p><b>Only {@code EXTERNAL} rows answer here, and that is the whole point of
 * the class.</b> The {@code domains} table holds four kinds and three of them
 * belong to a VM, which already has an access list of its own; if this adapter
 * identified those rows, a second access list could be hung on a domain whose
 * real authorization lives on its VM, and the two would disagree about who may
 * reach the same name. Every lookup below therefore filters on the kind rather
 * than trusting the id to name the right sort of row.</p>
 */
@Component
public class DomainResourceAdapter implements ResourceTypeAdapter {

    /** Every sentence the access machinery says about one of these names. */
    public static final ResourceAccessMessages MESSAGES = new ResourceAccessMessages(
            "해당 도메인이 존재하지 않습니다.",
            new ResourceAccessMessages.Refusal("이 도메인에 접근할 권한이 없습니다",
                    "이 도메인의 접근 목록에 등록되어 있지 않습니다. 자원 소유자에게 접근 권한을 요청해 주세요."),
            new ResourceAccessMessages.Refusal("접근 권한을 관리할 권한이 없습니다",
                    "이 도메인의 소유자 또는 워크스페이스 소유자만 접근 권한을 관리할 수 있습니다."),
            "이 도메인을 소유한 워크스페이스의 구성원만 접근 권한을 받을 수 있습니다. 먼저 워크스페이스에 추가해 주세요.",
            ErrorCodes.DNS_DOMAIN_ACCESS_GRANT_EXISTS,
            new ResourceAccessMessages.Refusal("이미 접근 권한이 있습니다",
                    "이 대상은 이미 이 도메인의 접근 목록에 있습니다. 등급을 바꾸려면 기존 항목을 수정해 주세요."));

    private static final ResourceAccessAudit AUDIT = new ResourceAccessAudit("dns_domain",
            AuditService.DNS_DOMAIN_ACCESS_GRANT_ADD, AuditService.DNS_DOMAIN_ACCESS_GRANT_UPDATE,
            AuditService.DNS_DOMAIN_ACCESS_GRANT_REMOVE, AuditService.DNS_DOMAIN_ACCESS_BREAK_GLASS);

    private final DomainRepository domainRepository;
    private final DnsDomainQueryService queryService;

    public DomainResourceAdapter(DomainRepository domainRepository,
            DnsDomainQueryService queryService) {
        this.domainRepository = domainRepository;
        this.queryService = queryService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.DOMAIN;
    }

    @Override
    public Optional<ResourceIdentity> identify(long resourceId) {
        // No filter on status: a released or reclaimed row keeps its history,
        // and the access list is what decides who may still read it.
        return domainRepository.findById(resourceId)
                .filter(DomainResourceAdapter::isExternal)
                .map(DomainResourceAdapter::identityOf);
    }

    @Override
    public Optional<ResourceIdentity> identifyByPublicId(UUID publicId) {
        return domainRepository.findByPublicId(publicId)
                .filter(DomainResourceAdapter::isExternal)
                .map(DomainResourceAdapter::identityOf);
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
        return domainRepository.findByWorkspaceIdAndKind(workspaceId, DomainKind.EXTERNAL).stream()
                .map(Domain::getId)
                .toList();
    }

    @Override
    public long countLiveInWorkspace(long workspaceId) {
        // A released name counts. It is still held out of the shared space for
        // this workspace, and deleting the workspace while it is would leave a
        // reservation whose owner no longer exists.
        return domainRepository.countByWorkspaceIdAndKindAndStatusNot(workspaceId,
                DomainKind.EXTERNAL, DomainStatus.REMOVED);
    }

    @Override
    public InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit) {
        // Reuses the list rather than re-deriving visibility: a restricted row
        // must say the same thing in both places.
        var page = queryService.listPage(actor, workspaceId,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
        return new InventoryHead(
                page.getContent().stream().map(DomainResourceAdapter::toSummary).toList(),
                page.getTotalElements());
    }

    private static boolean isExternal(Domain domain) {
        return domain.getKind() == DomainKind.EXTERNAL;
    }

    private static ResourceIdentity identityOf(Domain domain) {
        return new ResourceIdentity(domain.getId(), domain.getPublicId(), domain.getWorkspaceId(),
                domain.getFqdn(), null, domain.getStatus().name());
    }

    private static ResourceSummaryResponse toSummary(DnsDomainView domain) {
        return new ResourceSummaryResponse(domain.id(), ResourceType.DOMAIN, domain.fqdn(), null,
                domain.status().name(), domain.workspaceId(), domain.workspaceName(),
                domain.accessLimited(), domain.ownerNames(), domain.accessManageAllowed(),
                domain.createdAt());
    }
}
