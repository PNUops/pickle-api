package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Clock;
import kr.ac.pusan.pickle.admin.dto.AdminApprovalImageReference;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewRequest;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.AdminVmPlacementCandidate;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.provisioning.NodePlacementBudget;
import kr.ac.pusan.pickle.provisioning.VmCloneReservationService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Purpose-bound placement support does not open general host inventory to the institution tier. */
@RestController
@RequestMapping("/api/v1/admin/vm-placement")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN')")
public class AdminVmPlacementController {
    private final OrgRepository orgs;
    private final VmCloneReservationService reservations;
    private final Clock clock;
    private final kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces;

    public AdminVmPlacementController(OrgRepository orgs, VmCloneReservationService reservations, Clock clock,
            kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization,
            kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces) {
        this.orgs = orgs; this.reservations = reservations; this.clock = clock; this.authorization = authorization; this.workspaces = workspaces;
    }

    @PostMapping("/preview")
    @Operation(summary = "제안 VM 사양의 현재 한 VM 배치 조건 조회",
            description = "신청 저장 전과 승인 검토에서 같은 이미지 revision과 한 VM 사양을 조회합니다. "
                    + "호환 ACTIVE replica와 ACTIVE 노드, 실제 배치 용량 계산을 재사용합니다. "
                    + "용량을 예약하거나 승인 규칙을 바꾸지 않으며 다인 전체 생성이나 외부 적용 완료를 보장하지 않습니다.")
    @Transactional(readOnly = true)
    public AdminVmPlacementPreviewResponse previewAdminVmPlacement(@AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody AdminVmPlacementPreviewRequest request) {
        var org = orgs.findByPublicId(request.orgId())
                .filter(value -> placementAllowed(actor, value.getId(), request.workspaceId()))
                .orElseThrow(() -> ApiException.validationFailed(java.util.List.of(
                        new FieldValidationError("orgId", "신청을 승인할 수 있는 기관을 선택해 주세요."))));
        var observedAt = clock.instant();
        VmCloneReservationService.VmClonePlacementObservation observation;
        try {
            observation = reservations.observe(request.imageId(), new NodePlacementBudget.VmPlacementResources(
                    request.vcpu(), request.memoryMb(), request.diskGb()), observedAt);
        } catch (IllegalArgumentException missing) {
            throw ApiException.validationFailed(java.util.List.of(new FieldValidationError("imageId", missing.getMessage())));
        }
        var image = observation.image();
        var candidates = observation.candidates().stream().map(value -> new AdminVmPlacementCandidate(
                value.node().getPublicId(), value.node().getName(), value.fitsOneNow())).toList();
        return new AdminVmPlacementPreviewResponse(org.getPublicId(), observedAt, request.imageId(),
                new AdminApprovalImageReference(image.getPublicId(), image.getDisplayName(), image.getVersion(), observation.selectableNow()),
                candidates, candidates.stream().anyMatch(AdminVmPlacementCandidate::fitsOneNow));
    }
    private boolean placementAllowed(AuthenticatedUser actor, long orgId, java.util.UUID workspaceId) {
        if (actor.role().isSysTier()) { return actor.role() == UserRole.SYS_ADMIN; }
        if (workspaceId != null) {
            return workspaces.findByPublicId(workspaceId).flatMap(workspace -> authorization.resourceTarget(
                    kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup.VM, orgId, workspace.getId()))
                    .map(target -> authorization.decision(actor,
                            kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_DECIDE, target).allowed()
                            || authorization.decision(actor, kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_ROSTER_ISSUE, target).allowed()).orElse(false);
        }
        return authorization.institutionTarget(orgId).map(target -> java.util.stream.Stream.of(
                kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_DECIDE,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_ROSTER_ISSUE)
                .flatMap(work -> authorization.sources(actor, work).stream())
                .anyMatch(source -> target.institutionId().equals(source.requiredInstitutionId())
                        && source.workspaceScope().mode() == kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.Mode.ALL))
                .orElse(false);
    }

}
