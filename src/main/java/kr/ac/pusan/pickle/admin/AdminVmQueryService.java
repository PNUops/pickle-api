package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global VM support metadata reads with an optional institution filter.
 * Resource membership, credential reveal and actual interventions retain
 * their dedicated authorization.
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
    private final kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries institutionQueries;
    private final kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions;

    public AdminVmQueryService(VmRepository vmRepository, WorkspaceRepository workspaceRepository,
            OrgRepository orgRepository, VmSettingsService vmSettingsService,
            VmQueryService vmQueryService, AdminVmAccess adminVmAccess, Clock clock,
            kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries institutionQueries,
            kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions) {
        this.vmRepository = vmRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.vmSettingsService = vmSettingsService;
        this.vmQueryService = vmQueryService;
        this.adminVmAccess = adminVmAccess;
        this.clock = clock; this.institutionQueries = institutionQueries; this.permissions = permissions;
    }

    /**
     * Contract {@code GET /admin/vms/{vmId}} (v0.17.0): global support view
     * of the detail — the viewer is not a workspace member, so
     * {@code myResourceRole} is null and password reveal stays off.
     */
    @Transactional(readOnly = true)
    public VmDetailResponse get(AuthenticatedUser actor, UUID vmId) {
        Vm vm = adminVmAccess.requireReadableVm(actor, vmId);
        java.util.Set<String> work = adminVmAccess.allowedWorkIds(actor, vm);
        if (adminVmAccess.canReadMetadata(actor, vm)) {
            return vmQueryService.supportDetailOf(vm, actor).withInstitutionWorks(work);
        }
        Workspace workspace = workspaceRepository.findById(vm.getWorkspaceId()).orElseThrow(AdminVmAccess::vmNotFound);
        Org org = vm.getOrgId() == null ? null : orgRepository.findById(vm.getOrgId()).orElse(null);
        return VmDetailResponse.commandReference(vm, new kr.ac.pusan.pickle.vm.dto.VmReferences(workspace.getPublicId(),
                org == null ? null : org.getPublicId(), null, null, null), workspace.getName(), org == null ? null : org.getName(), work);
    }

    /** VM event metadata is separate from runtime observation and actual-institution audit identity. */
    @Transactional(readOnly = true)
    public PageResponse<VmEventResponse> events(AuthenticatedUser actor, UUID vmId, int page,
            int size) {
        Vm vm = adminVmAccess.requireReadableVm(actor, vmId);
        if (!adminVmAccess.canReadMetadata(actor, vm)) { throw AdminVmAccess.vmNotFound(); }
        boolean auditVisible = vm.getOrgId() != null && permissions.institution(actor,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.INSTITUTION_AUDIT_READ, vm.getOrgId(),
                kr.ac.pusan.pickle.user.UserRole.SYS_VIEWER, kr.ac.pusan.pickle.user.UserRole.SYS_MANAGER, kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN);
        return vmQueryService.eventsOf(vm.getId(), page, size, auditVisible);
    }

    @Transactional(readOnly = true)
    public PageResponse<VmSummaryResponse> list(AuthenticatedUser actor, UUID orgId, UUID workspaceId,
            VmStatus status, Integer expiringInDays, Boolean expired, String q, String sort,
            int page, int size) {
        permissions.requireReadSource(actor, kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries.VM_REFERENCE_WORKS.toArray(kr.ac.pusan.pickle.security.institution.InstitutionWork[]::new));
        OrgScope scope = scopeOrgId(actor, orgId);
        Pageable pageable = PageRequest.of(page, size,
                resolveSort(sort).and(Sort.by(Sort.Direction.DESC, "id")));
        // An id no org or workspace has filters to nothing, as a non-matching
        // numeric id did — it is a filter, not an addressed resource.
        Long scopedWorkspaceId = workspaceId == null ? null
                : workspaceRepository.findByPublicId(workspaceId).map(Workspace::getId).orElse(null);
        if (scope.orgIds().isEmpty() && !scope.isUnrestricted()
                || (workspaceId != null && scopedWorkspaceId == null)) {
            return PageResponse.of(List.of(), Page.empty(pageable));
        }
        List<Long> permittedIds = institutionQueries.vmReferences(actor);
        Specification<Vm> spec = (root, query, cb) -> root.get("id").in(permittedIds);
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
            List<Long> metadataIds = institutionQueries.vmMetadata(actor);
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.and(root.get("id").in(metadataIds), cb.like(cb.lower(root.get("hostname")), pattern, '\\'))));
        }
        if (!scope.isUnrestricted()) {
            spec = spec.and((root, query, cb) -> root.get("orgId").in(scope.orgIds()));
        }
        if (scopedWorkspaceId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("workspaceId"), scopedWorkspaceId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (expiringInDays != null || Boolean.TRUE.equals(expired) || "endDate".equals(sort) || "-endDate".equals(sort)) {
            List<Long> periodIds = institutionQueries.vmPeriodReferences(actor);
            spec = spec.and((root, query, cb) -> root.get("id").in(periodIds));
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
        Map<Long, Org> orgs = orgRepository.findAllById(
                        vms.stream().map(Vm::getOrgId).filter(java.util.Objects::nonNull)
                                .distinct().toList())
                .stream().collect(Collectors.toMap(Org::getId, java.util.function.Function.identity()));
        Map<Long, String> displayNames = vmSettingsService.displayNames(
                vms.stream().map(Vm::getId).toList());
        Map<Long, UUID> requestIds = vmQueryService.requestPublicIds(vms);
        Map<Long, UUID> deletionRequesterIds = vmQueryService.deletionRequesterPublicIds(vms);
        return PageResponse.of(vms.stream()
                .map(vm -> {
                    Workspace workspace = workspaces.get(vm.getWorkspaceId());
                    Org org = orgs.get(vm.getOrgId());
                    java.util.Set<String> work = adminVmAccess.allowedWorkIds(actor, vm);
                    if (!adminVmAccess.canReadMetadata(actor, vm)) {
                        return VmSummaryResponse.commandReference(vm, workspace == null ? null : workspace.getPublicId(),
                                workspace == null ? "" : workspace.getName(), org == null ? null : org.getPublicId(),
                                org == null ? null : org.getName(), work);
                    }
                    return VmSummaryResponse.from(vm,
                            workspace == null ? null : workspace.getPublicId(),
                            workspace == null ? "" : workspace.getName(),
                            org == null ? null : org.getPublicId(),
                            org == null ? null : org.getName(), displayNames.get(vm.getId()),
                            requestIds.get(vm.getRequestId()),
                            vm.getDeleteRequestedBy() == null ? null
                                    : deletionRequesterIds.get(vm.getDeleteRequestedBy())).withInstitutionWorks(work);
                })
                .toList(), result);
    }

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

    private OrgScope scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        return orgId != null && requested == null ? OrgScope.nothing() : OrgScope.of(requested);
    }
}
