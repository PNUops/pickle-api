package kr.ac.pusan.pickle.orgs;

import java.util.List;
import kr.ac.pusan.pickle.orgs.dto.OrgSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /orgs — active orgs a VM request can
 * target. USER-role callers do not see hidden orgs (test/seed fixtures);
 * manager-tier callers see every active org, hidden included, because the
 * admin console manages orgs through this same list.
 */
@RestController
@RequestMapping("/api/v1/orgs")
public class OrgController {

    private final OrgRepository orgRepository;

    public OrgController(OrgRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OrgSummaryResponse> listOrgs(@AuthenticationPrincipal AuthenticatedUser principal) {
        List<Org> orgs = principal.role() == UserRole.USER
                ? orgRepository.findByStatusAndHiddenFalseOrderByIdAsc(OrgStatus.ACTIVE)
                : orgRepository.findByStatusOrderByIdAsc(OrgStatus.ACTIVE);
        return orgs.stream()
                .map(OrgSummaryResponse::from)
                .toList();
    }
}
