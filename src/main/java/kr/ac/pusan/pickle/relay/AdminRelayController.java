package kr.ac.pusan.pickle.relay;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.relay.dto.AdminRelayView;
import kr.ac.pusan.pickle.relay.dto.RelayTokenResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, relay registry: observability list (SYS tier)
 * and sync-token issue (SYS_ADMIN only, sudo-mode — the response reveals a
 * live infrastructure credential exactly once).
 */
@RestController
@RequestMapping("/api/v1/admin/relays")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminRelayController {

    private final AdminRelayService adminRelayService;

    public AdminRelayController(AdminRelayService adminRelayService) {
        this.adminRelayService = adminRelayService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public List<AdminRelayView> listAdminRelays() {
        return adminRelayService.list();
    }

    @PostMapping("/{relayId}/token")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @RequireReauth
    public RelayTokenResponse issueAdminRelayToken(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID relayId,
            HttpServletRequest httpRequest) {
        return adminRelayService.issueToken(principal, relayId, clientIp(httpRequest));
    }
}
