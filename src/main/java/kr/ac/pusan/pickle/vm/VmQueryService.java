package kr.ac.pusan.pickle.vm;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.ProvisioningTaskView;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only VM views (contract tag {@code vms} — M2 has no lifecycle actions).
 * Visibility: members (VIEWER+) of the owning group. The contract defines no
 * 403 for the list, so a groupId filter outside my groups yields an empty page.
 */
@Service
public class VmQueryService {

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final IpAllocationRepository allocationRepository;
    private final ProvisioningTaskRepository taskRepository;

    public VmQueryService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            IpAllocationRepository allocationRepository, ProvisioningTaskRepository taskRepository) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.allocationRepository = allocationRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, Long groupId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        List<Long> groupIds = groupMemberRepository.findWithGroupByUserId(actor.id()).stream()
                .map(m -> m.getGroup().getId())
                .toList();
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
        return PageResponse.of(result.getContent().stream().map(VmSummaryResponse::from).toList(), result);
    }

    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다."));
        if (groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), actor.id()).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "VM 소유 그룹의 멤버만 조회할 수 있습니다.");
        }
        return VmDetailResponse.from(vm, ipAddress(vm), provisioning(vm));
    }

    /** The live address only: released/quarantined allocations show as null. */
    private String ipAddress(Vm vm) {
        if (vm.getIpAllocationId() == null) {
            return null;
        }
        return allocationRepository.findById(vm.getIpAllocationId())
                .filter(allocation -> allocation.getStatus() == AllocationStatus.ALLOCATED)
                .map(IpAllocation::getIp)
                .map(ip -> {
                    int slash = ip.indexOf('/');
                    return slash >= 0 ? ip.substring(0, slash) : ip;
                })
                .orElse(null);
    }

    /** The most recent async task of the VM; null when none exists yet. */
    private ProvisioningTaskView provisioning(Vm vm) {
        List<ProvisioningTask> tasks = taskRepository.findByVmIdOrderByIdDesc(vm.getId());
        return tasks.isEmpty() ? null : ProvisioningTaskView.from(tasks.getFirst());
    }
}
