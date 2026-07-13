package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code GET /admin/vms}: ORG_ADMIN hard-scoped to their own org,
 * SYS_ADMIN across orgs with the optional {@code orgId} filter. An ORG_ADMIN
 * naming another org answers 404 so cross-org existence stays private
 * (same convention as the vm-requests queue).
 */
@Service
public class AdminVmQueryService {

    /** Expiry filters never surface rows already leaving through deletion. */
    private static final List<VmStatus> EXPIRY_FILTER_EXCLUDED =
            List.of(VmStatus.DELETED, VmStatus.DELETING);

    private final VmRepository vmRepository;
    private final GroupRepository groupRepository;
    private final Clock clock;

    public AdminVmQueryService(VmRepository vmRepository, GroupRepository groupRepository,
            Clock clock) {
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, Long orgId, Long groupId,
            VmStatus status, Integer expiringInDays, Boolean expired, int page, int size) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Specification<Vm> spec = (root, query, cb) -> cb.conjunction();
        if (scopedOrgId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("orgId"), scopedOrgId));
        }
        if (groupId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("groupId"), groupId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        LocalDate today = ClockConfig.todayKst(clock);
        if (expiringInDays != null) {
            // today ≤ endDate ≤ today+N — already-expired rows are the expired
            // filter's business; combining both filters ANDs to empty by
            // design (contract).
            LocalDate horizon = today.plusDays(expiringInDays);
            spec = spec.and((root, query, cb) -> cb.and(
                    cb.between(root.get("endDate"), today, horizon),
                    cb.not(root.get("status").in(EXPIRY_FILTER_EXCLUDED))));
        }
        if (Boolean.TRUE.equals(expired)) {
            spec = spec.and((root, query, cb) -> cb.and(
                    cb.lessThan(root.get("endDate"), today),
                    cb.not(root.get("status").in(EXPIRY_FILTER_EXCLUDED))));
        }
        Page<Vm> result = vmRepository.findAll(spec, pageable);
        Map<Long, String> groupNames = groupRepository.findAllById(
                        result.getContent().stream().map(Vm::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(Group::getId, Group::getName));
        return PageResponse.of(result.getContent().stream()
                .map(vm -> VmSummaryResponse.from(vm, groupNames.getOrDefault(vm.getGroupId(), "")))
                .toList(), result);
    }

    /** ORG_ADMIN is pinned to their own org; another org's id answers 404. */
    private static Long scopeOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role() != UserRole.ORG_ADMIN) {
            return orgId;
        }
        if (actor.orgId() == null) {
            // Defensive: an ORG_ADMIN without a managed org sees nothing.
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !orgId.equals(actor.orgId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return actor.orgId();
    }
}
