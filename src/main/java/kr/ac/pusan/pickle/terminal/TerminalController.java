package kr.ac.pusan.pickle.terminal;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.terminal.dto.TerminalTicketResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-terminal ticket mint (contract {@code createTerminalSession}, v0.10.0). No
 * {@code @PreAuthorize}: authorization is service-layer (workspace MEMBER+, with
 * non-members/VIEWER/missing VM masked as 404). The ticket response is never
 * cached ({@code Cache-Control: no-store}).
 */
@RestController
@RequestMapping("/api/v1/vms/{vmId}/terminal-sessions")
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @PostMapping
    // ResponseEntity carries the no-store cache directive; the annotation is
    // what makes the 201 visible in the generated contract.
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TerminalTicketResponse> createTerminalSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        TerminalTicketResponse ticket = terminalService.mint(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ticket);
    }
}
