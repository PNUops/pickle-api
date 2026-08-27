package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.DisableUserRequest;
import kr.ac.pusan.pickle.admin.dto.AdminUpdateProfileRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin user surface (contract tag admin). List and detail answer for every
 * account to every admin role, viewers included — the one admin surface that is
 * not scoped by organisation (see {@link AdminUserQueryService}); disable,
 * enable and the MFA reset are SYS_ADMIN-only, and role changes go through
 * {@code AdminController} (per the permission matrix).
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
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
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

    /**
     * 프로필 정정. 직책·학번·소속 are write-once for the account holder, so this
     * is where they change after that; see {@code ProfileLock} for why the two
     * halves ship together.
     */
    @PatchMapping("/{userId}/profile")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public UserAdminDetailResponse updateUserProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId, @Valid @RequestBody AdminUpdateProfileRequest request,
            HttpServletRequest httpRequest) {
        return adminUserService.updateProfile(principal, userId, request, clientIp(httpRequest));
    }

    @PostMapping("/{userId}/mfa-reset")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public MessageResponse resetUserMfa(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID userId, HttpServletRequest httpRequest) {
        return adminUserService.resetMfa(principal, userId, clientIp(httpRequest));
    }
}
