package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.CreateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateUserAdminRequest;
import kr.ac.pusan.pickle.auth.dto.UserSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, org/user management subset — SYS_ADMIN only
 * (ORG_ADMIN gets 403 ACCESS_DENIED via the method-security gate). The
 * v0.20.0 org list read widens to the sys tier so SYS_MANAGER can see
 * DISABLED/hidden orgs too (writes stay SYS_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Every org regardless of status/hidden — the public {@code GET /orgs}
     * filters to ACTIVE for all roles, so a DISABLED org would otherwise be
     * invisible everywhere (the gap that motivated this op).
     */
    @GetMapping("/orgs")
    @PreAuthorize("hasAnyRole('SYS_MANAGER', 'SYS_ADMIN')")
    public List<OrgDetailResponse> listAdminOrgs() {
        return adminService.listOrgs();
    }

    @PostMapping("/orgs")
    @ResponseStatus(HttpStatus.CREATED)
    public OrgDetailResponse createOrg(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateOrgRequest request,
            HttpServletRequest httpRequest) {
        return adminService.createOrg(principal, request, clientIp(httpRequest));
    }

    @PatchMapping("/orgs/{orgId}")
    public OrgDetailResponse updateOrg(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long orgId,
            @Valid @RequestBody UpdateOrgRequest request,
            HttpServletRequest httpRequest) {
        return adminService.updateOrg(principal, orgId, request, clientIp(httpRequest));
    }

    @PatchMapping("/users/{userId}")
    public UserSummaryResponse updateUser(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long userId,
            @Valid @RequestBody UpdateUserAdminRequest request,
            HttpServletRequest httpRequest) {
        return adminService.updateUser(principal, userId, request, clientIp(httpRequest));
    }
}
