package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.publishing.dto.DnsDomainView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the names a person may see, and the one they asked for.
 *
 * <p>Visibility follows the shape the LLM key established rather than the one
 * the served domains use: membership of the owning workspace puts a row in the
 * list, and only a grant opens it. A served domain is reached through the VM it
 * publishes, which is why its listing joins VMs and this one cannot.</p>
 */
@Service
public class DnsDomainQueryService {

    private final DomainRepository domainRepository;
    private final DomainRecordRepository recordRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final SettingsService settingsService;

    public DnsDomainQueryService(DomainRepository domainRepository,
            DomainRecordRepository recordRepository, WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessResolver resourceAccessResolver, SettingsService settingsService) {
        this.domainRepository = domainRepository;
        this.recordRepository = recordRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.settingsService = settingsService;
    }

    /** The list, as a page, shared by the endpoint and the resource inventory. */
    @Transactional(readOnly = true)
    public Page<DnsDomainView> listPage(AuthenticatedUser actor, UUID workspaceId,
            Pageable pageable) {
        List<WorkspaceMember> memberships =
                workspaceMemberRepository.findWithWorkspaceByUserId(actor.id());
        Map<Long, Workspace> workspaces = memberships.stream()
                .collect(Collectors.toMap(m -> m.getWorkspace().getId(),
                        WorkspaceMember::getWorkspace, (first, second) -> first));
        List<Long> workspaceIds = List.copyOf(workspaces.keySet());
        Page<Domain> result;
        if (workspaceId != null) {
            // An unknown workspace id and one outside my memberships answer the
            // same empty page: the contract defines no 403 for the list.
            Long filterId = workspaceRepository.findByPublicId(workspaceId)
                    .map(Workspace::getId).orElse(null);
            result = filterId != null && workspaceIds.contains(filterId)
                    ? domainRepository.findByWorkspaceIdAndKindAndStatusNot(filterId,
                            DomainKind.EXTERNAL, DomainStatus.REMOVED, pageable)
                    : Page.empty(pageable);
        } else {
            result = workspaceIds.isEmpty()
                    ? Page.empty(pageable)
                    : domainRepository.findByWorkspaceIdInAndKindAndStatusNot(workspaceIds,
                            DomainKind.EXTERNAL, DomainStatus.REMOVED, pageable);
        }
        List<Domain> domains = result.getContent();
        Set<Long> ownedWorkspaceIds = memberships.stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(m -> m.getWorkspace().getId())
                .collect(Collectors.toSet());
        ResourceAccessResolver.ListAccess access = resourceAccessResolver.listAccess(
                ResourceType.DOMAIN, domains.stream().map(Domain::getId).toList(), actor.id());
        int graceDays = graceDays();
        return new PageImpl<>(domains.stream()
                .map(domain -> {
                    Workspace workspace = workspaces.get(domain.getWorkspaceId());
                    boolean open = access.reachable().contains(domain.getId());
                    return view(domain, workspace, graceDays, !open,
                            open ? List.of() : access.ownerNames()
                                    .getOrDefault(domain.getId(), List.of()),
                            ownedWorkspaceIds.contains(domain.getWorkspaceId()),
                            access.roles().get(domain.getId()),
                            open ? recordSetCount(domain.getId()) : 0);
                })
                .toList(), pageable, result.getTotalElements());
    }

    /**
     * One name, for a grant holder. An unknown id and one this person may not
     * see answer the same 404; a member of the owning workspace who can already
     * see it listed is refused in the open with 403.
     */
    @Transactional(readOnly = true)
    public DnsDomainView get(AuthenticatedUser actor, UUID domainId) {
        Domain domain = requireExternal(domainId);
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.DOMAIN,
                domain.getId(), domain.getWorkspaceId(), actor.id());
        standing.requireVisible(DomainResourceAdapter.MESSAGES);
        Workspace workspace = workspaceRepository.findById(domain.getWorkspaceId()).orElse(null);
        return view(domain, workspace, graceDays(), false, List.of(), standing.manages(),
                standing.role(), recordSetCount(domain.getId()));
    }

    /**
     * The row behind a public id, refusing a kind this platform serves.
     *
     * <p>Not found rather than a wrong-kind error: these paths are the surface
     * of one resource kind, and a served domain is simply not one of its rows.
     * Saying so would also confirm that some other kind of thing exists under
     * that id to somebody who cannot see it.</p>
     */
    Domain requireExternal(UUID domainId) {
        return domainRepository.findByPublicId(domainId)
                .filter(domain -> domain.getKind() == DomainKind.EXTERNAL)
                .orElseThrow(() -> DomainResourceAdapter.MESSAGES.notFound());
    }

    private int graceDays() {
        return settingsService.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS,
                SubdomainPolicy.DEFAULT_RESERVE_DAYS);
    }

    private int recordSetCount(long domainId) {
        return recordRepository.findByDomainIdAndStatusNotOrderByIdAsc(domainId,
                DomainRecordStatus.REMOVED).size();
    }

    private static DnsDomainView view(Domain domain, Workspace workspace, int graceDays,
            boolean limited, List<String> ownerNames, boolean manages,
            @Nullable ResourceRole myResourceRole, int recordSetCount) {
        Instant releasedAt = domain.getReleasedAt();
        return new DnsDomainView(
                domain.getPublicId(),
                domain.getFqdn(),
                domain.getRootDomain(),
                domain.getStatus(),
                domain.getRenewDueAt(),
                releasedAt,
                releasedAt == null ? null : releasedAt.plus(graceDays, ChronoUnit.DAYS),
                domain.getCreatedAt(),
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(),
                limited,
                manages,
                myResourceRole,
                ownerNames,
                recordSetCount);
    }
}
