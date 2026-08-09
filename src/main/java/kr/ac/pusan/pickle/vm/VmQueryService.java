package kr.ac.pusan.pickle.vm;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
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
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.PublicationAssembler;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
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
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final IpAddressResolver ipAddressResolver;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final VmEventRepository vmEventRepository;
    private final DomainRepository domainRepository;
    private final PublicationAssembler publicationAssembler;
    private final VmSettingsService vmSettingsService;
    private final String sshHost;

    public VmQueryService(VmRepository vmRepository, WorkspaceMemberRepository workspaceMemberRepository,
            VmAccessService vmAccessService,
            ResourceAccessGrantRepository grantRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            IpAddressResolver ipAddressResolver,
            ProvisioningTaskRepository provisioningTaskRepository,
            VmEventRepository vmEventRepository,
            DomainRepository domainRepository, PublicationAssembler publicationAssembler,
            VmSettingsService vmSettingsService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost) {
        this.vmRepository = vmRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.vmAccessService = vmAccessService;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.provisioningTaskRepository = provisioningTaskRepository;
        this.vmEventRepository = vmEventRepository;
        this.domainRepository = domainRepository;
        this.publicationAssembler = publicationAssembler;
        this.vmSettingsService = vmSettingsService;
        this.sshHost = sshHost == null || sshHost.isBlank() ? null : sshHost;
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, Long workspaceId, int page, int size) {
        Page<VmSummaryResponse> result = listPage(actor, workspaceId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(result.getContent(), result);
    }

    /**
     * The same list, as a {@link Page} — what the resource inventory reuses so
     * that one set of visibility rules serves both surfaces.
     */
    @Transactional(readOnly = true)
    public Page<VmSummaryResponse> listPage(AuthenticatedUser actor, Long workspaceId, Pageable pageable) {
        List<WorkspaceMember> memberships = workspaceMemberRepository.findWithWorkspaceByUserId(actor.id());
        Map<Long, String> workspaceNames = memberships.stream()
                .collect(Collectors.toMap(m -> m.getWorkspace().getId(), m -> m.getWorkspace().getName()));
        List<Long> workspaceIds = List.copyOf(workspaceNames.keySet());
        Page<Vm> result;
        if (workspaceId != null) {
            result = workspaceIds.contains(workspaceId)
                    ? vmRepository.findByWorkspaceId(workspaceId, pageable)
                    : Page.empty(pageable);
        } else {
            result = workspaceIds.isEmpty()
                    ? Page.empty(pageable)
                    : vmRepository.findByWorkspaceIdIn(workspaceIds, pageable);
        }
        List<Vm> vms = result.getContent();
        Map<Long, String> orgNames = orgNames(vms);
        Map<Long, String> displayNames = vmSettingsService.displayNames(
                vms.stream().map(Vm::getId).toList());
        Set<Long> ownedWorkspaceIds = memberships.stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(m -> m.getWorkspace().getId())
                .collect(Collectors.toSet());
        VmListAccess access = listAccess(actor.id(), vms);
        return new PageImpl<>(vms.stream()
                .map(vm -> {
                    String workspaceName = workspaceNames.getOrDefault(vm.getWorkspaceId(), "");
                    String displayName = displayNames.get(vm.getId());
                    // Only a grant opens the row. A workspace owner without one gets
                    // the same restricted row as anyone else, plus the flag that
                    // lets the console offer them the access list — the way back
                    // in for a VM whose own owner is gone.
                    if (access.reachable().contains(vm.getId())) {
                        return VmSummaryResponse.from(vm, workspaceName, orgNames.get(vm.getOrgId()),
                                displayName);
                    }
                    return VmSummaryResponse.restricted(vm, workspaceName, displayName,
                            access.ownerNames().getOrDefault(vm.getId(), List.of()),
                            ownedWorkspaceIds.contains(vm.getWorkspaceId()));
                })
                .toList(), pageable, result.getTotalElements());
    }

    /** Which of these VMs the requester may see in full, and who to ask about the rest. */
    private record VmListAccess(Set<Long> reachable, Map<Long, List<String>> ownerNames) {
    }

    private VmListAccess listAccess(long userId, List<Vm> vms) {
        if (vms.isEmpty()) {
            return new VmListAccess(Set.of(), Map.of());
        }
        List<ResourceAccessGrant> grants = grantRepository.findByResourceTypeAndResourceIdIn(
                ResourceType.VM, vms.stream().map(Vm::getId).toList());
        Set<Long> reachable = new java.util.HashSet<>();
        Map<Long, List<Long>> ownerIds = new java.util.LinkedHashMap<>();
        for (ResourceAccessGrant grant : grants) {
            if (grant.getGranteeType() == AccessGranteeType.WORKSPACE
                    || Long.valueOf(userId).equals(grant.getUserId())) {
                reachable.add(grant.getResourceId());
            }
            if (grant.getRole() == ResourceRole.OWNER && grant.getUserId() != null) {
                ownerIds.computeIfAbsent(grant.getResourceId(), key -> new java.util.ArrayList<>())
                        .add(grant.getUserId());
            }
        }
        Map<Long, String> names = userRepository.findAllById(ownerIds.values().stream()
                        .flatMap(List::stream).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        Map<Long, List<String>> ownerNames = new java.util.LinkedHashMap<>();
        ownerIds.forEach((vmId, ids) -> ownerNames.put(vmId, ids.stream()
                .map(names::get).filter(java.util.Objects::nonNull).toList()));
        return new VmListAccess(reachable, ownerNames);
    }

    /** Batch org-name join for the summary views (avoids N+1). */
    private Map<Long, String> orgNames(List<Vm> vms) {
        List<Long> orgIds = vms.stream().map(Vm::getOrgId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (orgIds.isEmpty()) {
            return Map.of();
        }
        return orgRepository.findAllById(orgIds).stream()
                .collect(Collectors.toMap(Org::getId, Org::getName));
    }

    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, long vmId) {
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
        String workspaceName = workspaceRepository.findById(vm.getWorkspaceId())
                .map(Workspace::getName).orElse("");
        String orgName = vm.getOrgId() == null ? null
                : orgRepository.findById(vm.getOrgId()).map(Org::getName).orElse(null);
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
                .map(publicationAssembler::toPublication)
                .toList();
        boolean passwordRevealAllowed = myResourceRole != null && myResourceRole.atLeast(
                vmSettingsService.role(vmId, VmSettingsService.PASSWORD_REVEAL_MIN_ROLE));
        return VmDetailResponse.from(vm, workspaceName, orgName, displayName, ipAddress, sshHost,
                myResourceRole, passwordRevealAllowed, accessManageAllowed, provisioning,
                publications);
    }

    /** Newest-first lifecycle history (contract op {@code listVmEvents}). */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> events(AuthenticatedUser actor, long vmId, int page, int size) {
        requireVisibleVm(actor, vmId);
        return eventsOf(vmId, page, size);
    }

    /**
     * History page for an <b>already authorized</b> VM — shared by the
     * member-scoped {@link #events} and the org-scoped admin surface.
     */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> eventsOf(long vmId, int page, int size) {
        Page<VmEvent> result = vmEventRepository.findByVmIdOrderByIdDesc(vmId,
                PageRequest.of(page, size));
        return PageResponse.of(result.getContent().stream().map(VmEventResponse::from).toList(),
                result);
    }

    /**
     * Unknown VM and existing-but-invisible VM both answer 404, so a
     * non-member cannot probe which VM ids exist (masking, contract v0.3.2 —
     * consistent with the power/delete paths).
     */
    private Vm requireVisibleVm(AuthenticatedUser actor, long vmId) {
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
