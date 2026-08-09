package kr.ac.pusan.pickle.vm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupRepository;
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
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only VM views (contract tag {@code vms}). Visibility: members
 * (VIEWER+) of the owning group. The contract defines no 403 for the list,
 * so a groupId filter outside my groups yields an empty page; detail and
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
    private final GroupMemberRepository groupMemberRepository;
    private final VmAccessService vmAccessService;
    private final GroupRepository groupRepository;
    private final OrgRepository orgRepository;
    private final IpAddressResolver ipAddressResolver;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final VmEventRepository vmEventRepository;
    private final DomainRepository domainRepository;
    private final PublicationAssembler publicationAssembler;
    private final VmSettingsService vmSettingsService;
    private final String sshHost;

    public VmQueryService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            VmAccessService vmAccessService,
            GroupRepository groupRepository, OrgRepository orgRepository,
            IpAddressResolver ipAddressResolver,
            ProvisioningTaskRepository provisioningTaskRepository,
            VmEventRepository vmEventRepository,
            DomainRepository domainRepository, PublicationAssembler publicationAssembler,
            VmSettingsService vmSettingsService,
            @Value("${pickle.ssh.advertised-host:}") String sshHost) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.vmAccessService = vmAccessService;
        this.groupRepository = groupRepository;
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
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, Long groupId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        List<GroupMember> memberships = groupMemberRepository.findWithGroupByUserId(actor.id());
        Map<Long, String> groupNames = memberships.stream()
                .collect(Collectors.toMap(m -> m.getGroup().getId(), m -> m.getGroup().getName()));
        List<Long> groupIds = List.copyOf(groupNames.keySet());
        Page<Vm> result;
        if (groupId != null) {
            result = groupIds.contains(groupId)
                    ? vmRepository.findByGroupId(groupId, pageable)
                    : Page.empty(pageable);
        } else {
            result = groupIds.isEmpty()
                    ? Page.empty(pageable)
                    : vmRepository.findByGroupIdIn(groupIds, pageable);
        }
        List<Vm> vms = result.getContent();
        Map<Long, String> orgNames = orgNames(vms);
        Map<Long, String> displayNames = vmSettingsService.displayNames(
                vms.stream().map(Vm::getId).toList());
        return PageResponse.of(vms.stream()
                .map(vm -> VmSummaryResponse.from(vm, groupNames.getOrDefault(vm.getGroupId(), ""),
                        orgNames.get(vm.getOrgId()), displayNames.get(vm.getId())))
                .toList(), result);
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
        return detailOf(access.requireVisible(), access.role());
    }

    /**
     * Assembles the full contract {@code VmDetail} for an <b>already
     * authorized</b> VM — shared by the member-scoped {@link #get} and admin
     * flows (period update) whose authorization is org-scoped instead.
     * {@code myResourceRole} is the requester's role in the owning group (null for
     * a non-member admin); it drives {@code passwordRevealAllowed} and the
     * console's settings-section visibility.
     */
    @Transactional(readOnly = true)
    public VmDetailResponse detailOf(Vm vm, ResourceRole myResourceRole) {
        long vmId = vm.getId();
        // History-preserving joins: a DELETED vm's group/org may have been
        // deleted afterwards, so this deliberately reads all groups/orgs.
        String groupName = groupRepository.findById(vm.getGroupId())
                .map(Group::getName).orElse("");
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
        return VmDetailResponse.from(vm, groupName, orgName, displayName, ipAddress, sshHost,
                myResourceRole, passwordRevealAllowed, provisioning, publications);
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
