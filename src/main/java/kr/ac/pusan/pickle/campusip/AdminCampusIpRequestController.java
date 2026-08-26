package kr.ac.pusan.pickle.campusip;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.campusip.dto.AdminCampusIpRequestView;
import kr.ac.pusan.pickle.campusip.dto.UpdateCampusIpRequestStatusRequest;
import kr.ac.pusan.pickle.common.web.PageResponse;
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

/**
 * Contract tag {@code admin}, 교내 IP 신청 processing: list (SYS tier) and
 * status transitions (SYS_ADMIN only — granting records a real campus
 * address).
 */
@RestController
@RequestMapping("/api/v1/admin/campus-ip-requests")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminCampusIpRequestController {

    private final CampusIpRequestService campusIpRequestService;

    public AdminCampusIpRequestController(CampusIpRequestService campusIpRequestService) {
        this.campusIpRequestService = campusIpRequestService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AdminCampusIpRequestView> listAdminCampusIpRequests(
            @RequestParam(required = false) CampusIpRequestStatus status,
            @RequestParam(required = false) UUID vmId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return campusIpRequestService.adminList(status, vmId, page, size);
    }

    @PostMapping("/{requestId}/status")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public AdminCampusIpRequestView updateAdminCampusIpRequestStatus(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID requestId,
            @Valid @RequestBody UpdateCampusIpRequestStatusRequest request,
            HttpServletRequest httpRequest) {
        return campusIpRequestService.updateStatus(principal, requestId, request,
                clientIp(httpRequest));
    }
}
