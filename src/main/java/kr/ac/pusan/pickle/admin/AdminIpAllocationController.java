package kr.ac.pusan.pickle.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.IpAllocationResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.ipam.IpPool;
import kr.ac.pusan.pickle.ipam.IpPoolRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, IP allocation registry (M5) — read-only list of
 * {@code ip_allocations} with pool/VM context, SYS_ADMIN only. RELEASED rows
 * stay visible as history. Query-only, so no separate service layer.
 */
@RestController
@RequestMapping("/api/v1/admin/ip-allocations")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminIpAllocationController {

    private final IpAllocationRepository ipAllocationRepository;
    private final IpPoolRepository ipPoolRepository;
    private final VmRepository vmRepository;

    public AdminIpAllocationController(IpAllocationRepository ipAllocationRepository,
            IpPoolRepository ipPoolRepository, VmRepository vmRepository) {
        this.ipAllocationRepository = ipAllocationRepository;
        this.ipPoolRepository = ipPoolRepository;
        this.vmRepository = vmRepository;
    }

    @GetMapping
    public PageResponse<IpAllocationResponse> listIpAllocations(
            @RequestParam(required = false) Long poolId,
            @RequestParam(required = false) AllocationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Specification<IpAllocation> spec = (root, query, cb) -> cb.conjunction();
        if (poolId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("poolId"), poolId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("allocatedAt"), Sort.Order.desc("id")));
        Page<IpAllocation> result = ipAllocationRepository.findAll(spec, pageable);

        Map<Long, String> poolNames = ipPoolRepository.findAllById(result.getContent().stream()
                        .map(IpAllocation::getPoolId).distinct().toList()).stream()
                .collect(Collectors.toMap(IpPool::getId, IpPool::getName));
        Map<Long, Vm> vms = vmRepository.findAllById(result.getContent().stream()
                        .map(IpAllocation::getVmId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Vm::getId, Function.identity()));

        List<IpAllocationResponse> content = result.getContent().stream()
                .map(allocation -> IpAllocationResponse.from(allocation,
                        poolNames.get(allocation.getPoolId()),
                        allocation.getVmId() == null ? null : vms.get(allocation.getVmId())))
                .toList();
        return PageResponse.of(content, result);
    }
}
