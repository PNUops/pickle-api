package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.NodeMetricsResponse;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateNodeStatusRequest;
import kr.ac.pusan.pickle.proxmox.RrdTimeframe;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, node inventory — sys-tier capacity view plus
 * the v0.21.0 status transition (SYS_ADMIN-only operational-state write:
 * placement only picks ACTIVE nodes, so MAINTENANCE/OFFLINE realises
 * drain-from-placement without touching existing guests). Small reference
 * list, plain array per the contract.
 */
@RestController
@RequestMapping("/api/v1/admin/nodes")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminNodeController {

    private final AdminNodeQueryService adminNodeQueryService;
    private final AdminInventoryService adminInventoryService;
    private final AdminNodeMetricsService adminNodeMetricsService;

    public AdminNodeController(AdminNodeQueryService adminNodeQueryService,
            AdminInventoryService adminInventoryService,
            AdminNodeMetricsService adminNodeMetricsService) {
        this.adminNodeQueryService = adminNodeQueryService;
        this.adminInventoryService = adminInventoryService;
        this.adminNodeMetricsService = adminNodeMetricsService;
    }

    @GetMapping
    public List<NodeSummaryResponse> listAdminNodes() {
        return adminNodeQueryService.listNodes();
    }

    /** Live host usage series from the hypervisor (contract v0.35.0). */
    @GetMapping("/{nodeId}/metrics")
    public NodeMetricsResponse getAdminNodeMetrics(@PathVariable UUID nodeId,
            @Parameter(description = "조회 구간 — HOUR/DAY/WEEK/MONTH/YEAR "
                    + "(해상도는 구간에 따라 거칠어짐)")
            @RequestParam(defaultValue = "HOUR") RrdTimeframe timeframe) {
        return adminNodeMetricsService.metrics(nodeId, timeframe);
    }

    @PatchMapping("/{nodeId}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public NodeSummaryResponse updateAdminNode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID nodeId,
            @Valid @RequestBody UpdateNodeStatusRequest request,
            HttpServletRequest httpRequest) {
        return adminInventoryService.updateNodeStatus(principal, nodeId, request,
                clientIp(httpRequest));
    }
}
