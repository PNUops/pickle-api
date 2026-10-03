package kr.ac.pusan.pickle.mail;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.mail.dto.MailDeliveryDetailResponse;
import kr.ac.pusan.pickle.mail.dto.MailDeliveryView;
import kr.ac.pusan.pickle.mail.dto.RequestMailSelectionResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminMailDeliveryController {
    private final MailDeliveryQueryService service;

    public AdminMailDeliveryController(MailDeliveryQueryService service) { this.service = service; }

    @GetMapping("/mail-deliveries")
    public PageResponse<MailDeliveryView> listAdminMailDeliveries(
            @RequestParam(required = false) String sourceKind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String queueState,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) UUID announcementId,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(sourceKind, status, queueState, event, email, requestId, announcementId, orgId, page, size);
    }

    @GetMapping("/mail-deliveries/{deliveryId}")
    public MailDeliveryDetailResponse getAdminMailDelivery(@PathVariable UUID deliveryId) {
        return service.detail(deliveryId);
    }

    @PostMapping("/mail-deliveries/{deliveryId}/resend")
    @PreAuthorize("hasAnyRole('SYS_MANAGER', 'SYS_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendAdminMailDelivery(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID deliveryId, HttpServletRequest request) {
        return service.resend(actor, deliveryId, clientIp(request));
    }

    @GetMapping("/requests/{requestId}/notification-selection")
    public RequestMailSelectionResponse getAdminRequestNotificationSelection(@PathVariable UUID requestId) {
        return service.selection(requestId);
    }
}
