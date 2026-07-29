package kr.ac.pusan.pickle.campusip;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.campusip.dto.CampusIpRequestView;
import kr.ac.pusan.pickle.campusip.dto.CreateCampusIpRequest;
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
 * Contract tag {@code campus-ip}: 교내 IP 신청 self-service. Authorization is
 * service-layer group scoping only (same shape as port forwarding and
 * publishing): reads need membership, writes need OWNER/EDITOR — no role
 * tier gate.
 */
@RestController
@RequestMapping("/api/v1/vms/{vmId}/campus-ip-requests")
public class CampusIpRequestController {

    private final CampusIpRequestService campusIpRequestService;

    public CampusIpRequestController(CampusIpRequestService campusIpRequestService) {
        this.campusIpRequestService = campusIpRequestService;
    }

    @GetMapping
    public List<CampusIpRequestView> listVmCampusIpRequests(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return campusIpRequestService.list(principal, vmId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampusIpRequestView requestVmCampusIp(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @Valid @RequestBody CreateCampusIpRequest request, HttpServletRequest httpRequest) {
        return campusIpRequestService.create(principal, vmId, request, clientIp(httpRequest));
    }

    /** 204: the cancel is pure DB state — there is nothing to converge. */
    @DeleteMapping("/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelVmCampusIpRequest(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @PathVariable long requestId, HttpServletRequest httpRequest) {
        campusIpRequestService.cancel(principal, vmId, requestId, clientIp(httpRequest));
    }
}
