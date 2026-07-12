package kr.ac.pusan.pickle.sshgw;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.sshgw.SshGatewayRouteService.RouteOutcome;
import kr.ac.pusan.pickle.sshgw.dto.RouteDenied;
import kr.ac.pusan.pickle.sshgw.dto.RouteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal SSH-gateway route resolution (docs/api/internal.md Link 1). Called by
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

    public InternalSshGatewayController(SshGatewayRouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping("/route")
    public ResponseEntity<?> route(@Valid @RequestBody RouteRequest request, HttpServletRequest http) {
        // The authenticated transport peer (the sshgw LXC); distinct from the
        // reported client IP in the body, which sshpiper recovered from PROXY.
        String gatewayPeer = http.getRemoteAddr();
        RouteOutcome outcome = routeService.resolve(request.slug(), request.sourceIp(), gatewayPeer);
        if (outcome.granted()) {
            return ResponseEntity.ok(outcome.route());
        }
        return ResponseEntity.status(outcome.status()).body(new RouteDenied(outcome.reason()));
    }
}
