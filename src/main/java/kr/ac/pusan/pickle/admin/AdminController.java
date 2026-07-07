package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.admin.dto.CreateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateUserAdminRequest;
import kr.ac.pusan.pickle.auth.dto.UserSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, org/user management subset — SYS_ADMIN only
 * (ORG_ADMIN gets 403 ACCESS_DENIED via the method-security gate).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/orgs")
    public ResponseEntity<OrgDetailResponse> createOrg(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateOrgRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminService.createOrg(principal, request, clientIp(httpRequest)));
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
