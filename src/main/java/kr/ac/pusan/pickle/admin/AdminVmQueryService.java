package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.AdminVmAccess;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
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

    /**
     * Contract {@code sort} whitelist — free-form property names never reach
     * the ORM. {@code endDate} keeps period-less VMs last in both directions;
     * every choice is stabilised with a secondary {@code id desc} below.
     */
    private static final Map<String, Sort> SORTS = Map.of(
            "name", Sort.by(Sort.Order.asc("name")),
            "-name", Sort.by(Sort.Order.desc("name")),
            "endDate", Sort.by(Sort.Order.asc("endDate").nullsLast()),
            "-endDate", Sort.by(Sort.Order.desc("endDate").nullsLast()),
            "createdAt", Sort.by(Sort.Order.asc("createdAt")),
            "-createdAt", Sort.by(Sort.Order.desc("createdAt")));

    private final VmRepository vmRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final VmSettingsService vmSettingsService;
    private final VmQueryService vmQueryService;
    private final AdminVmAccess adminVmAccess;
    private final Clock clock;

    public AdminVmQueryService(VmRepository vmRepository, WorkspaceRepository workspaceRepository,
            OrgRepository orgRepository, VmSettingsService vmSettingsService,
            VmQueryService vmQueryService, AdminVmAccess adminVmAccess, Clock clock) {
        this.vmRepository = vmRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.vmSettingsService = vmSettingsService;
        this.vmQueryService = vmQueryService;
        this.adminVmAccess = adminVmAccess;
        this.clock = clock;
    }

    /**
     * Contract {@code GET /admin/vms/{vmId}} (v0.17.0): org-scoped admin view
     * of the full detail — the viewer is not a workspace member, so
     * {@code myResourceRole} is null and password reveal stays off.
     */
    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, UUID vmId) {
        Vm vm = adminVmAccess.requireOrgScopedVm(actor, vmId);
        return vmQueryService.detailOf(vm, null);
    }

    /** Contract {@code GET /admin/vms/{vmId}/events} (v0.17.0): org-scoped history. */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> events(AuthenticatedUser actor, UUID vmId, int page,
            int size) {
        return vmQueryService.eventsOf(adminVmAccess.requireOrgScopedVm(actor, vmId).getId(),
                page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, UUID orgId, UUID workspaceId,
            VmStatus status, Integer expiringInDays, Boolean expired, String q, String sort,
            int page, int size) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        Pageable pageable = PageRequest.of(page, size,
                resolveSort(sort).and(Sort.by(Sort.Direction.DESC, "id")));
        // An id no org or workspace has filters to nothing, as a non-matching
        // numeric id did — it is a filter, not an addressed resource.
        Long scopedWorkspaceId = workspaceId == null ? null
                : workspaceRepository.findByPublicId(workspaceId).map(Workspace::getId).orElse(null);
        if ((orgId != null && scopedOrgId == null) || (workspaceId != null && scopedWorkspaceId == null)) {
            return PageResponse.of(List.of(), Page.empty(pageable));
        }
        Specification<Vm> spec = (root, query, cb) -> cb.conjunction();
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("hostname")), pattern, '\\')));
        }
        if (scopedOrgId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("orgId"), scopedOrgId));
        }
        if (scopedWorkspaceId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("workspaceId"), scopedWorkspaceId));
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
        List<Vm> vms = result.getContent();
        Map<Long, Workspace> workspaces = workspaceRepository.findAllById(
                        vms.stream().map(Vm::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, java.util.function.Function.identity()));
        Map<Long, String> orgNames = orgRepository.findAllById(
                        vms.stream().map(Vm::getOrgId).filter(java.util.Objects::nonNull)
                                .distinct().toList())
                .stream().collect(Collectors.toMap(Org::getId, Org::getName));
        Map<Long, String> displayNames = vmSettingsService.displayNames(
                vms.stream().map(Vm::getId).toList());
        Map<Long, UUID> requestIds = vmQueryService.requestPublicIds(vms);
        return PageResponse.of(vms.stream()
                .map(vm -> {
                    Workspace workspace = workspaces.get(vm.getWorkspaceId());
                    return VmSummaryResponse.from(vm,
                            workspace == null ? null : workspace.getPublicId(),
                            workspace == null ? "" : workspace.getName(),
                            orgNames.get(vm.getOrgId()), displayNames.get(vm.getId()),
                            requestIds.get(vm.getRequestId()));
                })
                .toList(), result);
    }

    /** ORG_ADMIN is pinned to their own org; another org's id answers 404. */
    private static Sort resolveSort(String sort) {
        if (sort == null) {
            return Sort.unsorted();
        }
        Sort resolved = SORTS.get(sort);
        if (resolved == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("sort",
                    "정렬 기준이 올바르지 않습니다. (허용: " + String.join(", ",
                            SORTS.keySet().stream().sorted().toList()) + ")")));
        }
        return resolved;
    }

    /** JPQL LIKE 특수문자를 이스케이프해 사용자 입력이 와일드카드로 해석되지 않게 한다. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Long scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        if (!actor.role().isOrgTier()) {
            return requested;
        }
        if (actor.orgId() == null) {
            // Defensive: an org-tier actor without a managed org sees nothing.
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !actor.orgId().equals(requested)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return actor.orgId();
    }
}
