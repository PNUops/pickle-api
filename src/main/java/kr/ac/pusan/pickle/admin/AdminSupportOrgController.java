package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Comparator;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminSupportOrgOptionResponse;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support-orgs")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminSupportOrgController {
    private final OrgRepository orgs;

    public AdminSupportOrgController(OrgRepository orgs) { this.orgs = orgs; }

    @GetMapping
    @Operation(summary = "학생 지원 조회의 기관 필터 목록",
            description = "비활성 기관을 포함한 기관 ID·이름·상태만 조회합니다. 기관 설정이나 자격증명, 배정·운영 권한을 제공하지 않습니다.")
    @Transactional(readOnly = true)
    public List<AdminSupportOrgOptionResponse> listAdminSupportOrgs(@AuthenticationPrincipal AuthenticatedUser actor) {
        StudentSupportPolicy.requireRead(actor);
        return orgs.findAll().stream().sorted(Comparator.comparing(Org::getName).thenComparing(Org::getId))
                .map(org -> new AdminSupportOrgOptionResponse(org.getPublicId(), org.getName(), org.getStatus())).toList();
    }
}
