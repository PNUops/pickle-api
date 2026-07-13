package kr.ac.pusan.pickle.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.dto.NotificationView;
import kr.ac.pusan.pickle.notification.dto.ReadAllResponse;
import kr.ac.pusan.pickle.notification.dto.UnreadCountResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code notifications}: the per-user inbox. Every endpoint is
 * strictly self-scoped — other users' rows answer 404 (masked) and no filter
 * can widen the scope.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationQueryService queryService;
    private final NotificationService notificationService;

    public NotificationController(NotificationQueryService queryService,
            NotificationService notificationService) {
        this.queryService = queryService;
        this.notificationService = notificationService;
    }

    @GetMapping
    public PageResponse<NotificationView> listNotifications(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queryService.list(principal.id(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse getUnreadNotificationCount(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return new UnreadCountResponse(queryService.unreadCount(principal.id()));
    }

    @PostMapping("/{notificationId}/read")
    public NotificationView markNotificationRead(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long notificationId) {
        return notificationService.markRead(principal.id(), notificationId);
    }

    @PostMapping("/read-all")
    public ReadAllResponse markAllNotificationsRead(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return new ReadAllResponse(notificationService.markAllRead(principal.id()));
    }
}
