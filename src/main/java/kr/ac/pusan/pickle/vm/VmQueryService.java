package kr.ac.pusan.pickle.vm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only VM views (contract tag {@code vms}). Visibility: members
 * (VIEWER+) of the owning group. The contract defines no 403 for the list,
 * so a groupId filter outside my groups yields an empty page; detail and
 * events mask a VM's existence from non-members as 404 (contract v0.3.2,
 * same policy as the power/delete paths).
 *
 * <p>The detail view assembles the M3 lifecycle surface: {@code provisioning}
 * is the newest task unless it finished cleanly (contract: in-flight or
 * last-failed; DONE → null), {@code deletion} maps the delete_* intent,
 * {@code initialPasswordAvailable} mirrors the one-shot plaintext column and
 * {@code ipAddress} resolves the IPAM allocation.</p>
 */
@Service
public class VmQueryService {

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final IpAllocationRepository ipAllocationRepository;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final VmEventRepository vmEventRepository;

    public VmQueryService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            GroupRepository groupRepository, IpAllocationRepository ipAllocationRepository,
            ProvisioningTaskRepository provisioningTaskRepository,
            VmEventRepository vmEventRepository) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
        this.ipAllocationRepository = ipAllocationRepository;
        this.provisioningTaskRepository = provisioningTaskRepository;
        this.vmEventRepository = vmEventRepository;
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
        return PageResponse.of(result.getContent().stream()
                .map(vm -> VmSummaryResponse.from(vm, groupNames.getOrDefault(vm.getGroupId(), "")))
                .toList(), result);
    }

    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, long vmId) {
        Vm vm = requireVisibleVm(actor, vmId);
        String groupName = groupRepository.findById(vm.getGroupId())
                .map(Group::getName).orElse("");
        String ipAddress = liveIpAddress(vm);
        ProvisioningTaskResponse provisioning = provisioningTaskRepository
                .findByVmIdOrderByIdDesc(vmId).stream()
                .findFirst()
                .filter(task -> task.getStatus() != ProvisioningTaskStatus.DONE)
                .map(ProvisioningTaskResponse::from)
                .orElse(null);
        return VmDetailResponse.from(vm, groupName, ipAddress, provisioning);
    }

    /** Newest-first lifecycle history (contract op {@code listVmEvents}). */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> events(AuthenticatedUser actor, long vmId, int page, int size) {
        requireVisibleVm(actor, vmId);
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
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(VmQueryService::vmNotFound);
        if (groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), actor.id()).isEmpty()) {
            throw vmNotFound();
        }
        return vm;
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }

    /**
     * The live address only: released/quarantined allocations show as null,
     * and an allocation re-claimed by another VM (stale pointer left by a
     * crashed release) is never shown as this VM's address.
     */
    private String liveIpAddress(Vm vm) {
        if (vm.getIpAllocationId() == null) {
            return null;
        }
        return ipAllocationRepository.findById(vm.getIpAllocationId())
                .filter(allocation -> allocation.getStatus() == AllocationStatus.ALLOCATED)
                .filter(allocation -> Objects.equals(allocation.getVmId(), vm.getId()))
                .map(IpAllocation::getIp)
                .map(ip -> {
                    int slash = ip.indexOf('/');
                    return slash >= 0 ? ip.substring(0, slash) : ip;
                })
                .orElse(null);
    }
}
