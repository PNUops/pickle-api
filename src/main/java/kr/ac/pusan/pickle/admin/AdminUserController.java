package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.DisableUserRequest;
import kr.ac.pusan.pickle.admin.dto.UserAdminDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UserAdminViewResponse;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
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
 * Admin user surface (contract tag admin). List/detail are visible to
 * ORG_ADMIN (own-org derived scope) and SYS_ADMIN; disable/enable are
 * SYS_ADMIN-only (per the permission matrix).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminUserController {

    private final AdminUserQueryService adminUserQueryService;
    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserQueryService adminUserQueryService,
            AdminUserService adminUserService) {
        this.adminUserQueryService = adminUserQueryService;
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public PageResponse<UserAdminViewResponse> listUsers(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @Size(min = 1, max = 100) String q,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UUID orgId,
            @io.swagger.v3.oas.annotations.Parameter(schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"name", "-name", "email", "-email", "createdAt", "-createdAt"}))
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminUserQueryService.listUsers(principal, q, status, role, orgId, sort, page, size);
    }

    @GetMapping("/{userId}")
    public UserAdminDetailResponse getUser(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId) {
        return adminUserQueryService.getUser(principal, userId);
    }

    @PostMapping("/{userId}/disable")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public UserAdminDetailResponse disableUser(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId, @Valid @RequestBody DisableUserRequest request,
            HttpServletRequest httpRequest) {
        return adminUserService.disable(principal, userId, request.reason(), clientIp(httpRequest));
    }

    @PostMapping("/{userId}/enable")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public UserAdminDetailResponse enableUser(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId, HttpServletRequest httpRequest) {
        return adminUserService.enable(principal, userId, clientIp(httpRequest));
    }

    @PostMapping("/{userId}/mfa-reset")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public MessageResponse resetUserMfa(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId, HttpServletRequest httpRequest) {
        return adminUserService.resetMfa(principal, userId, clientIp(httpRequest));
    }
}
