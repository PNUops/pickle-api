package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewRequest;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final AdminVmPlacementPreviewService previews;
    private final kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces;

    public AdminVmPlacementController(OrgRepository orgs, AdminVmPlacementPreviewService previews,
            kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization,
            kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces) {
        this.orgs = orgs; this.previews = previews; this.authorization = authorization; this.workspaces = workspaces;
    }

    @PostMapping("/preview")
    @Operation(summary = "제안 VM 사양과 수량의 현재 배치 여유 조회",
            description = "같은 사양 1~200대의 배치 가능 수량과 노드별 예산, 할당, 잔여와 계획 배치 후 잔여를 조회해요. "
                    + "실제 배치 예산과 호환 이미지 조건을 사용하고 관리 상태와 실제 연결 관측을 구분해요. "
                    + "확인되지 않은 노드는 가용 합계에서 제외해요. 용량을 예약하거나 승인 규칙을 바꾸지 않으며 전체 생성 완료를 보장하지 않아요.")
    public AdminVmPlacementPreviewResponse previewAdminVmPlacement(@AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody AdminVmPlacementPreviewRequest request) {
        var org = orgs.findByPublicId(request.orgId())
                .filter(value -> placementAllowed(actor, value.getId(), request.workspaceId()))
                .orElseThrow(() -> ApiException.validationFailed(java.util.List.of(
                        new FieldValidationError("orgId", "신청을 승인할 수 있는 기관을 선택해 주세요."))));
        try {
            return previews.preview(org.getPublicId(), request);
        } catch (IllegalArgumentException missing) {
            throw ApiException.validationFailed(java.util.List.of(new FieldValidationError("imageId", missing.getMessage())));
        }
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
