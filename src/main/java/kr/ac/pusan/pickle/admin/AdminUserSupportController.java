package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminUserProfileImpactRequest;
import kr.ac.pusan.pickle.admin.dto.AdminUserProfileImpactResponse;
import kr.ac.pusan.pickle.admin.dto.AdminUserResourceAccessResponse;
import kr.ac.pusan.pickle.admin.dto.AdminUserSupportResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only support surfaces; no impersonation or authorization mutation. */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminUserSupportController {
    private final AdminUserSupportQueryService support;
    private final AdminUserProfileImpactService impact;

    public AdminUserSupportController(AdminUserSupportQueryService support, AdminUserProfileImpactService impact) {
        this.support = support;
        this.impact = impact;
    }

    @GetMapping("/support")
    @Operation(summary = "사용자의 관계와 실제 신청 결과 조회",
            description = "구성원 관계는 사용자 상세의 조회 범위를 따릅니다. 리소스와 신청 결과는 조회 가능한 기관으로 "
                    + "제한하며 초대는 승인 권한자에게만 보입니다. 저장된 학번으로 대기 초대를 계정과 연결하는 조회는 "
                    + "시스템 관리자에게만 허용됩니다. 생성 대기와 리소스의 실제 상태를 구분합니다.")
    public AdminUserSupportResponse getAdminUserSupport(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @RequestParam(required = false) UUID orgId) {
        return support.support(actor, userId, orgId);
    }

    @GetMapping("/support/access")
    @Operation(summary = "사용자와 리소스의 공통 접근 조건 조회",
            description = "관리자가 조회할 수 있는 기관의 지정 리소스에 대해 구성원과 부여, 계정 및 리소스 상태를 "
                    + "설명합니다. 실제 사용자 인가와 연결 상태는 변경하지 않으며 종류별 작업 성공을 보장하지 않습니다.")
    public AdminUserResourceAccessResponse getAdminUserResourceAccess(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @RequestParam(required = false) UUID orgId,
            @RequestParam ResourceType type, @RequestParam UUID resourceId) {
        return support.diagnose(actor, userId, orgId, type, resourceId);
    }

    @PostMapping("/profile-impact")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Operation(summary = "프로필 정정과 계정 활성화의 초대 영향 미리보기",
            description = "제안된 프로필 또는 복원할 계정 상태에서 수락 가능한 초대와 가입 대기 신청의 다음 상태를 "
                    + "조회합니다. 값을 저장하거나 초대를 수락하지 않습니다. 미리보기는 예약이 아니며 생성 대기는 실제 생성 완료가 아닙니다.")
    public AdminUserProfileImpactResponse previewAdminUserProfileImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @Valid @RequestBody AdminUserProfileImpactRequest request) {
        return impact.preview(actor, userId, request);
    }
}
