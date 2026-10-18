package kr.ac.pusan.pickle.security.institution;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Arrays;
import java.util.List;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionAuthorityResponse;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/institution-authority")
public class InstitutionAuthorityController {
    private final JdbcTemplate jdbc;
    public InstitutionAuthorityController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Operation(operationId = "getMyInstitutionAuthority", summary = "현재 계정의 기관 업무와 부여 출처 조회",
            description = "표시 역할과 실제 기관 업무를 구분합니다. 권한 상태를 확인할 수 없으면 INVALID로 표시하며 개인 접근권한과 시스템 역할을 바꾸지 않습니다.")
    public InstitutionAuthorityResponse getMyInstitutionAuthority(@AuthenticationPrincipal AuthenticatedUser actor) {
        var context = actor.institutionContext();
        var grants = context.authority().map(authority -> authority.assignments().stream().map(assignment -> {
            String name = jdbc.query("select name from orgs where public_id=?", (rs,index)->rs.getString(1), assignment.institutionId())
                    .stream().findFirst().orElse("기관 정보 확인 필요");
            return new InstitutionAuthorityResponse.InstitutionGrantView(assignment.institutionId(), name, InstitutionPermissionView.from(assignment));
        }).toList()).orElseGet(List::of);
        var catalogue = actor.role().isOrgTier() || actor.role().isSysTier() ? Arrays.stream(InstitutionWork.values()).map(work ->
                new InstitutionAuthorityResponse.InstitutionWorkView(work.id(), InstitutionWorkLabels.label(work), work.group(),
                        work.boundary(), work.write(), work.selectable(), InstitutionWorkCatalogue.includedInFull(work),
                        InstitutionWorkCatalogue.isGlobalResourceReadInFull(work))).toList()
                : List.<InstitutionAuthorityResponse.InstitutionWorkView>of();
        return new InstitutionAuthorityResponse(context.state().name(), context.failure().map(Enum::name).orElse(null),
                actor.role(), InstitutionWorkCatalogue.REVISION, context.authority().map(InstitutionAuthority::fingerprint).orElse(null), grants, catalogue);
    }
}
