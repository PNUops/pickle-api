package kr.ac.pusan.pickle.terminal;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.terminal.dto.TerminalSessionView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin web-terminal surface (contract tag {@code admin}, v0.10.0). The list is
 * SYS tier (all) / ORG tier (own-org sessions only, service-layer scoped);
 * force-terminate is SYS_ADMIN-only (method gate overrides the class gate).
 */
@RestController
@RequestMapping("/api/v1/admin/terminal-sessions")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminTerminalController {

    private final TerminalService terminalService;

    public AdminTerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public List<TerminalSessionView> listAdminTerminalSessions(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return terminalService.list(principal);
    }

    @PostMapping("/{sessionId}/terminate")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminateTerminalSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String sessionId,
            HttpServletRequest httpRequest) {
        terminalService.terminate(principal, sessionId, clientIp(httpRequest));
    }
}
