package kr.ac.pusan.pickle.sshgw;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.sshgw.SshGatewayRouteService.RouteOutcome;
import kr.ac.pusan.pickle.sshgw.dto.RouteDenied;
import kr.ac.pusan.pickle.sshgw.dto.RouteRequest;
import kr.ac.pusan.pickle.sshgw.dto.SessionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal SSH-gateway route resolution (the internal SSH gateway route contract). Called by
 * sshpiper on the sshgw LXC on every incoming SSH connection. Access is gated by
 * the dedicated {@code /internal/**} filter chain (bearer + source-IP allowlist
 * + rate limit); this controller assumes an authenticated caller.
 *
 * <p>{@link Hidden} keeps it out of the springdoc runtime spec: {@code
 * /internal/**} is deliberately excluded from the public console contract
 * (openapi.yaml) and its drift test.</p>
 */
@Hidden
@RestController
@RequestMapping("/internal/sshgw")
public class InternalSshGatewayController {

    private final SshGatewayRouteService routeService;
    private final SshGatewaySessionService sessionService;

    public InternalSshGatewayController(SshGatewayRouteService routeService,
            SshGatewaySessionService sessionService) {
        this.routeService = routeService;
        this.sessionService = sessionService;
    }

    @PostMapping("/route")
    public ResponseEntity<?> route(@Valid @RequestBody RouteRequest request, HttpServletRequest http) {
        // The authenticated transport peer (the sshgw LXC); distinct from the
        // reported client IP in the body, which sshpiper recovered from PROXY.
        String gatewayPeer = http.getRemoteAddr();
        RouteOutcome outcome = routeService.resolve(request, gatewayPeer);
        if (outcome.granted()) {
            return ResponseEntity.ok(outcome.route());
        }
        return ResponseEntity.status(outcome.status()).body(new RouteDenied(outcome.reason()));
    }

    /**
     * Authenticated session audit (PipeStart, post-verification). Fire-and-forget:
     * always 204, even on a best-effort resolution miss — the session is already
     * live and must not be torn down by this call (the internal SSH gateway route contract).
     */
    @PostMapping("/session")
    public ResponseEntity<Void> session(@Valid @RequestBody SessionRequest request,
            HttpServletRequest http) {
        sessionService.recordSession(request, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
