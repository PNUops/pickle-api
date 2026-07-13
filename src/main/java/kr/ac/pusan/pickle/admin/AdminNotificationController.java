package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.admin.dto.AdminNotificationResponse;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.NotificationStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, notification delivery log (M5) — email-channel
 * send log with recipient/event/status filters and the FAILED-only resend,
 * SYS_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    public AdminNotificationController(AdminNotificationService adminNotificationService) {
        this.adminNotificationService = adminNotificationService;
    }

    @GetMapping
    public PageResponse<AdminNotificationResponse> listAdminNotifications(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminNotificationService.list(status, event, email, page, size);
    }

    @PostMapping("/{notificationId}/resend")
    public ResponseEntity<MessageResponse> resendAdminNotification(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long notificationId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted()
                .body(adminNotificationService.resend(principal, notificationId,
                        clientIp(httpRequest)));
    }
}
