package kr.ac.pusan.pickle.relay;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.relay.dto.CreatePortForwardingRequest;
import kr.ac.pusan.pickle.relay.dto.PortForwardingView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code port-forwarding}: self-service relay port forwarding
 * for a VM. Authorization is service-layer group scoping (publishing
 * pattern): reads need membership, writes need OWNER/EDITOR.
 */
@RestController
@RequestMapping("/api/v1/vms/{vmId}/port-forwardings")
public class PortForwardingController {

    private final PortForwardingService portForwardingService;

    public PortForwardingController(PortForwardingService portForwardingService) {
        this.portForwardingService = portForwardingService;
    }

    @GetMapping
    public List<PortForwardingView> listVmPortForwardings(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return portForwardingService.list(principal, vmId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortForwardingView createVmPortForwarding(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @Valid @RequestBody CreatePortForwardingRequest request,
            HttpServletRequest httpRequest) {
        return portForwardingService.create(principal, vmId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{portForwardingId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse deleteVmPortForwarding(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @PathVariable long portForwardingId, HttpServletRequest httpRequest) {
        return portForwardingService.delete(principal, vmId, portForwardingId,
                clientIp(httpRequest));
    }
}
