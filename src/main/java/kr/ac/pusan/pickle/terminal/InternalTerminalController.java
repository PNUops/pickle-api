package kr.ac.pusan.pickle.terminal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.terminal.TerminalService.RedeemOutcome;
import kr.ac.pusan.pickle.terminal.dto.TerminalRedeemDenied;
import kr.ac.pusan.pickle.terminal.dto.TerminalRedeemRequest;
import kr.ac.pusan.pickle.terminal.dto.TerminalRevalidateRequest;
import kr.ac.pusan.pickle.terminal.dto.TerminalRevalidateResponse;
import kr.ac.pusan.pickle.terminal.dto.TerminalSessionEndRequest;
import kr.ac.pusan.pickle.terminal.dto.TerminalSessionStartRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal web-terminal control link, bridge → pickle-api (the internal
 * web-terminal contract). Called by {@code sshgw-terminal-bridge} on LXC 102. Access is gated
 * by the shared {@code /internal/**} filter chain (bearer {@code PICKLE_SSHGW_TOKEN}
 * + source 172.30.1.30 + rate limit); this controller assumes an authorized caller.
 *
 * <p>{@link Hidden} keeps it out of the springdoc runtime spec — {@code /internal/**}
 * is deliberately excluded from the public console contract and its drift test.</p>
 */
@Hidden
@RestController
@RequestMapping("/internal/terminal")
public class InternalTerminalController {

    private final TerminalService terminalService;

    public InternalTerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    /** Atomic single-use consume + authorization re-check → 200 allow / 403 deny. */
    @PostMapping("/redeem")
    public ResponseEntity<?> redeem(@Valid @RequestBody TerminalRedeemRequest request) {
        RedeemOutcome outcome = terminalService.redeem(request.ticket());
        if (outcome.granted()) {
            return ResponseEntity.ok(outcome.response());
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new TerminalRedeemDenied(outcome.reason()));
    }

    /** SSH channel live → register mirror + audit session_start. 204 / 409. */
    @PostMapping("/session-start")
    public ResponseEntity<Void> sessionStart(
            @Valid @RequestBody TerminalSessionStartRequest request) {
        terminalService.sessionStart(request.sessionId(), request.clientIp());
        return ResponseEntity.noContent().build();
    }

    /** Session closed (any cause) → remove mirror + audit session_end. 204 idempotent. */
    @PostMapping("/session-end")
    public ResponseEntity<Void> sessionEnd(@Valid @RequestBody TerminalSessionEndRequest request) {
        terminalService.sessionEnd(request);
        return ResponseEntity.noContent().build();
    }

    /** 60s revalidation poll → {allow:true} / {allow:false, reason}. */
    @PostMapping("/revalidate")
    public TerminalRevalidateResponse revalidate(
            @Valid @RequestBody TerminalRevalidateRequest request) {
        return terminalService.revalidate(request.sessionId());
    }
}
