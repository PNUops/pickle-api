package kr.ac.pusan.pickle.orgs;

import java.util.List;
import kr.ac.pusan.pickle.orgs.dto.OrgSummaryResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code reference}: GET /orgs — active orgs a VM request can target. */
@RestController
@RequestMapping("/api/v1/orgs")
public class OrgController {

    private final OrgRepository orgRepository;

    public OrgController(OrgRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OrgSummaryResponse> listOrgs() {
        return orgRepository.findByStatusOrderByIdAsc(OrgStatus.ACTIVE).stream()
                .map(OrgSummaryResponse::from)
                .toList();
    }
}
