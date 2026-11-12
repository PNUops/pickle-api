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
            description = "학생 지원과 관계별 조회 업무 및 대상 범위에 따라 구성원과 초대, 신청, 리소스 관계를 조회해요. 기관은 선택 필터예요. "
                    + "저장된 학번과 대기 초대의 연결, 생성 대기와 실제 리소스 상태를 구분해요.")
    public AdminUserSupportResponse getAdminUserSupport(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @RequestParam(required = false) UUID orgId) {
        return support.support(actor, userId, orgId);
    }

    @GetMapping("/support/access")
    @Operation(summary = "사용자와 리소스의 공통 접근 조건 조회",
            description = "접근 목록 읽기 업무와 대상 범위에 따라 지정 리소스의 구성원과 부여, 공통 상태를 설명해요. "
                    + "학생 지원과 독립적인 진단은 연결된 계정의 최소 상태를 제공하며 실제 사용자 인가와 연결 상태는 바꾸지 않아요.")
    public AdminUserResourceAccessResponse getAdminUserResourceAccess(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @RequestParam(required = false) UUID orgId,
            @RequestParam ResourceType type, @RequestParam UUID resourceId) {
        return support.diagnose(actor, userId, orgId, type, resourceId);
    }

    @PostMapping("/profile-impact")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN')")
    @Operation(summary = "프로필 정정과 계정 활성화의 초대 영향 미리보기",
            description = "제안된 프로필 또는 복원할 계정 상태에서 수락 가능한 초대와 가입 대기 신청의 다음 상태를 "
                    + "조회해요. 프로필 정정 업무를 가진 기관 계정과 시스템 관리자가 정정을 미리 보며 계정 활성화 미리보기는 시스템 관리자만 수행해요. "
                    + "값을 저장하거나 초대를 수락하지 않아요. 미리보기는 예약이 아니며 생성 대기는 실제 생성 완료가 아니에요.")
    public AdminUserProfileImpactResponse previewAdminUserProfileImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @Valid @RequestBody AdminUserProfileImpactRequest request) {
        return impact.preview(actor, userId, request);
    }
}
