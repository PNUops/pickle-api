package kr.ac.pusan.pickle.vm;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.PublicationAssembler;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmReferences;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only VM views (contract tag {@code vms}). Visibility: members
 * (VIEWER+) of the owning workspace. The contract defines no 403 for the list,
 * so a workspaceId filter outside my workspaces yields an empty page; detail and
 * events mask a VM's existence from non-members as 404 (contract v0.3.2,
 * same policy as the power/delete paths).
 *
 * <p>The detail view assembles the lifecycle surface: {@code provisioning}
 * is the newest task unless it finished cleanly (contract: in-flight or
 * last-failed; DONE → null), {@code deletion} maps the delete_* intent,
 * {@code passwordAvailable} mirrors the stored ciphertext column (re-viewable —
 * it stays true after reveals) and {@code ipAddress} resolves the IPAM
 * allocation.</p>
 */
@Service
public class VmQueryService {

    private final VmRepository vmRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final VmAccessService vmAccessService;
    private final ResourceAccessResolver resourceAccessResolver;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final OsImageRepository osImageRepository;
    private final RequestRepository requestRepository;
    private final IpAddressResolver ipAddressResolver;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final VmEventRepository vmEventRepository;
    private final DomainRepository domainRepository;
    private final PublicationAssembler publicationAssembler;
    private final VmSettingsService vmSettingsService;
    private final String sshHost;

    public VmQueryService(VmRepository vmRepository, WorkspaceMemberRepository workspaceMemberRepository,
            VmAccessService vmAccessService,
            ResourceAccessResolver resourceAccessResolver,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            OsImageRepository osImageRepository, RequestRepository requestRepository,
            IpAddressResolver ipAddressResolver,
            ProvisioningTaskRepository provisioningTaskRepository,
            VmEventRepository vmEventRepository,
            DomainRepository domainRepository, PublicationAssembler publicationAssembler,
            VmSettingsService vmSettingsService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost) {
        this.vmRepository = vmRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.vmAccessService = vmAccessService;
        this.resourceAccessResolver = resourceAccessResolver;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.osImageRepository = osImageRepository;
        this.requestRepository = requestRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.provisioningTaskRepository = provisioningTaskRepository;
        this.vmEventRepository = vmEventRepository;
        this.domainRepository = domainRepository;
        this.publicationAssembler = publicationAssembler;
        this.vmSettingsService = vmSettingsService;
        this.sshHost = sshHost == null || sshHost.isBlank() ? null : sshHost;
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, UUID workspaceId, int page, int size) {
        Page<VmSummaryResponse> result = listPage(actor, workspaceId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(result.getContent(), result);
    }

    /**
     * The same list, as a {@link Page} — what the resource inventory reuses so
     * that one set of visibility rules serves both surfaces.
     */
    @Transactional(readOnly = true)
    public Page<VmSummaryResponse> listPage(AuthenticatedUser actor, UUID workspaceId, Pageable pageable) {
        List<WorkspaceMember> memberships = workspaceMemberRepository.findWithWorkspaceByUserId(actor.id());
        Map<Long, Workspace> workspaces = memberships.stream()
                .collect(Collectors.toMap(m -> m.getWorkspace().getId(), WorkspaceMember::getWorkspace,
                        (first, second) -> first));
        List<Long> workspaceIds = List.copyOf(workspaces.keySet());
        Page<Vm> result;
        if (workspaceId != null) {
            // An unknown workspace id and one outside my memberships answer the
            // same empty page: the contract defines no 403 for the list.
            Long filterId = workspaceRepository.findByPublicId(workspaceId)
                    .map(Workspace::getId).orElse(null);
            result = filterId != null && workspaceIds.contains(filterId)
                    ? vmRepository.findByWorkspaceId(filterId, pageable)
                    : Page.empty(pageable);
        } else {
            result = workspaceIds.isEmpty()
                    ? Page.empty(pageable)
                    : vmRepository.findByWorkspaceIdIn(workspaceIds, pageable);
        }
        List<Vm> vms = result.getContent();
        Map<Long, Org> orgs = orgs(vms);
        Map<Long, UUID> requestIds = requestPublicIds(vms);
        Map<Long, UUID> deletionRequesterIds = deletionRequesterPublicIds(vms);
        Map<Long, String> displayNames = vmSettingsService.displayNames(
                vms.stream().map(Vm::getId).toList());
        Set<Long> ownedWorkspaceIds = memberships.stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(m -> m.getWorkspace().getId())
                .collect(Collectors.toSet());
        ResourceAccessResolver.ListAccess access = resourceAccessResolver.listAccess(
                ResourceType.VM, vms.stream().map(Vm::getId).toList(), actor.id());
        return new PageImpl<>(vms.stream()
                .map(vm -> {
                    Workspace workspace = workspaces.get(vm.getWorkspaceId());
                    UUID workspacePublicId = workspace == null ? null : workspace.getPublicId();
                    String workspaceName = workspace == null ? "" : workspace.getName();
                    String displayName = displayNames.get(vm.getId());
                    Org org = orgs.get(vm.getOrgId());
                    // Only a grant opens the row. A workspace owner without one gets
                    // the same restricted row as anyone else, plus the flag that
                    // lets the console offer them the access list — the way back
                    // in for a VM whose own owner is gone.
                    if (access.reachable().contains(vm.getId())) {
                        return VmSummaryResponse.from(vm, workspacePublicId, workspaceName,
                                org == null ? null : org.getPublicId(),
                                org == null ? null : org.getName(), displayName,
                                requestIds.get(vm.getRequestId()),
                                vm.getDeleteRequestedBy() == null ? null
                                        : deletionRequesterIds.get(vm.getDeleteRequestedBy()));
                    }
                    return VmSummaryResponse.restricted(vm, workspacePublicId, workspaceName, displayName,
                            access.ownerNames().getOrDefault(vm.getId(), List.of()),
                            ownedWorkspaceIds.contains(vm.getWorkspaceId()));
                })
                .toList(), pageable, result.getTotalElements());
    }

    /**
     * Batch request-reference join for the summary views. The summary reports
     * which request produced the VM and had no join for it while the id was
     * the VM's own column; a public id lives on the request row, so it needs
     * one. Shared with the admin list so both pages pay for it once.
     */
    public Map<Long, UUID> requestPublicIds(List<Vm> vms) {
        List<Long> requestIds = vms.stream().map(Vm::getRequestId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        return requestRepository.findAllById(requestIds).stream()
                .collect(Collectors.toMap(Request::getId, Request::getPublicId));
    }

    /** Batch join for the actor named by a pending deletion in summary rows. */
    public Map<Long, UUID> deletionRequesterPublicIds(List<Vm> vms) {
        List<Long> userIds = vms.stream().map(Vm::getDeleteRequestedBy)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getPublicId));
    }

    /** Batch org-name join for the summary views (avoids N+1). */
    private Map<Long, Org> orgs(List<Vm> vms) {
        List<Long> orgIds = vms.stream().map(Vm::getOrgId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (orgIds.isEmpty()) {
            return Map.of();
        }
        return orgRepository.findAllById(orgIds).stream()
                .collect(Collectors.toMap(Org::getId, java.util.function.Function.identity()));
    }

    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, UUID vmId) {
        VmAccess access = vmAccessService.of(actor, vmId);
        return detailOf(access.requireVisible(), access.role(), access.manages());
    }

    /**
     * Assembles the full contract {@code VmDetail} for an <b>already
     * authorized</b> VM — shared by the member-scoped {@link #get} and admin
     * flows (period update) whose authorization is org-scoped instead.
     * {@code myResourceRole} is the requester's role in the owning workspace (null for
     * a non-member admin); it drives {@code passwordRevealAllowed} and the
     * console's settings-section visibility.
     */
    @Transactional(readOnly = true)
    public VmDetailResponse detailOf(Vm vm, ResourceRole myResourceRole) {
        return detailOf(vm, myResourceRole, false);
    }

    /** Same view, told whether the requester may manage the access list and delete. */
    @Transactional(readOnly = true)
    public VmDetailResponse detailOf(Vm vm, ResourceRole myResourceRole,
            boolean accessManageAllowed) {
        long vmId = vm.getId();
        // History-preserving joins: a DELETED vm's workspace/org may have been
        // deleted afterwards, so this deliberately reads all workspaces/orgs.
        Workspace workspace = workspaceRepository.findById(vm.getWorkspaceId()).orElse(null);
        String workspaceName = workspace == null ? "" : workspace.getName();
        Org org = vm.getOrgId() == null ? null
                : orgRepository.findById(vm.getOrgId()).orElse(null);
        String orgName = org == null ? null : org.getName();
        VmReferences refs = new VmReferences(
                workspace == null ? null : workspace.getPublicId(),
                org == null ? null : org.getPublicId(),
                vm.getImageId() == null ? null
                        : osImageRepository.findById(vm.getImageId()).map(OsImage::getPublicId).orElse(null),
                vm.getRequestId() == null ? null
                        : requestRepository.findById(vm.getRequestId()).map(Request::getPublicId).orElse(null),
                vm.getDeleteRequestedBy() == null ? null
                        : userRepository.findById(vm.getDeleteRequestedBy()).map(User::getPublicId).orElse(null));
        String displayName = vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME);
        String ipAddress = liveIpAddress(vm);
        ProvisioningTaskResponse provisioning = provisioningTaskRepository
                .findByVmIdOrderByIdDesc(vmId).stream()
                .findFirst()
                .filter(task -> task.getStatus() != ProvisioningTaskStatus.DONE)
                .map(ProvisioningTaskResponse::from)
                .orElse(null);
        // Every domain that is actually serving (live route), id order — a
        // released/reserved row is not a publication (contract:
        // PublicationView.route is required).
        List<PublicationView> publications = domainRepository.findByVmId(vmId).stream()
                .filter(domain -> domain.getStatus() != DomainStatus.REMOVED)
                .filter(publicationAssembler::hasLiveRoute)
                .sorted(java.util.Comparator.comparing(Domain::getId))
                .map(domain -> publicationAssembler.toPublication(domain, vm.getPublicId()))
                .toList();
        boolean passwordRevealAllowed = myResourceRole != null && myResourceRole.atLeast(
                vmSettingsService.role(vmId, VmSettingsService.PASSWORD_REVEAL_MIN_ROLE));
        return VmDetailResponse.from(vm, refs, workspaceName, orgName, displayName, ipAddress, sshHost,
                myResourceRole, passwordRevealAllowed, accessManageAllowed, provisioning,
                publications);
    }

    /** Newest-first lifecycle history (contract op {@code listVmEvents}). */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> events(AuthenticatedUser actor, UUID vmId, int page, int size) {
        return eventsOf(requireVisibleVm(actor, vmId).getId(), page, size, false);
    }

    /**
     * History page for an <b>already authorized</b> VM — shared by the
     * member-scoped {@link #events} and the org-scoped admin surface.
     *
     * <p>{@code revealAdminActor} is what separates the two audiences. A
     * member sees which of their own people acted, and sees an administrator's
     * intervention only as an intervention; an administrator sees the name
     * behind it too. Everything else about the page is identical.
     *
     * <p>A row whose surface is {@link VmActorKind#UNKNOWN} is withheld from
     * the member audience on the same terms as an administrator's: the actor is
     * recorded, but whether they acted as a colleague or reached in is not, and
     * naming them would publish an administrator on the chance that they were
     * one. An administrator reading the same page sees the name, because for
     * that audience there is nothing to withhold.
     */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> eventsOf(long vmId, int page, int size,
            boolean revealAdminActor) {
        Page<VmEvent> result = vmEventRepository.findByVmIdOrderByIdDesc(vmId,
                PageRequest.of(page, size));
        Map<Long, User> actors = userRepository.findAllById(result.getContent().stream()
                        .map(VmEvent::getActorId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, user -> user));
        return PageResponse.of(result.getContent().stream()
                .map(event -> {
                    boolean withheld = !revealAdminActor
                            && (event.getActorKind() == VmActorKind.ADMIN
                                || event.getActorKind() == VmActorKind.UNKNOWN);
                    User who = withheld ? null : actors.get(event.getActorId());
                    return VmEventResponse.from(event, who == null ? null : who.getPublicId(),
                            who == null ? null : who.getName());
                })
                .toList(), result);
    }

    /**
     * Unknown VM and existing-but-invisible VM both answer 404, so a
     * non-member cannot probe which VM ids exist (masking, contract v0.3.2 —
     * consistent with the power/delete paths).
     */
    private Vm requireVisibleVm(AuthenticatedUser actor, UUID vmId) {
        return vmAccessService.of(actor, vmId).requireVisible();
    }

    /**
     * The live address only: released/quarantined allocations show as null,
     * and an allocation re-claimed by another VM (stale pointer left by a
     * crashed release) is never shown as this VM's address (shared owned +
     * ALLOCATED guard, {@link IpAddressResolver}).
     */
    private String liveIpAddress(Vm vm) {
        return ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
    }
}
