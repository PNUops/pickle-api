package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.InstitutionLlmAccountReference;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account choices for a new, institution-bound LLM roster command. */
@RestController
@RequestMapping("/api/v1/admin/llm/roster-account-references")
@Tag(name = "admin", description = "관리자 API")
@PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
public class AdminLlmRosterReferenceController {
    private final InstitutionLlmAccountReferences references;
    public AdminLlmRosterReferenceController(InstitutionLlmAccountReferences references) { this.references = references; }

    @GetMapping
    @Operation(operationId = "listAdminLlmRosterAccountReferences", summary = "새 LLM 명단 발급의 사업 계정 선택 참조",
            description = "실제 기관과 살아 있는 워크스페이스에 대한 명단 발급과 키 예산 업무를 같은 권한 원천에서 확인합니다. "
                    + "기존 신청이나 리소스, 개인 구성원 자격은 필요하지 않습니다. 계정 ID·이름·선택 가능 상태와 적용 가능한 기본 정책만 반환합니다.")
    public List<InstitutionLlmAccountReference> list(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam UUID orgId, @RequestParam UUID workspaceId) {
        return references.forRoster(actor, orgId, workspaceId);
    }
}
