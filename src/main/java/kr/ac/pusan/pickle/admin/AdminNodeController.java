package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, node inventory view — SYS_ADMIN only (ORG_ADMIN
 * gets 403 ACCESS_DENIED via the method-security gate). A small reference
 * list, so a plain array per the contract.
 */
@RestController
@RequestMapping("/api/v1/admin/nodes")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminNodeController {

    private final AdminNodeQueryService adminNodeQueryService;

    public AdminNodeController(AdminNodeQueryService adminNodeQueryService) {
        this.adminNodeQueryService = adminNodeQueryService;
    }

    @GetMapping
    public List<NodeSummaryResponse> listAdminNodes() {
        return adminNodeQueryService.listNodes();
    }
}
