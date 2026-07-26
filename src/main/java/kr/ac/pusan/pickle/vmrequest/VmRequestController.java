package kr.ac.pusan.pickle.vmrequest;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vmrequest.dto.CreateVmRequestRequest;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code vm-requests} (openapi.yaml v0.2.3, server /api/v1). */
@RestController
@RequestMapping("/api/v1/vm-requests")
public class VmRequestController {

    private final VmRequestService vmRequestService;

    public VmRequestController(VmRequestService vmRequestService) {
        this.vmRequestService = vmRequestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VmRequestDetailResponse createVmRequest(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateVmRequestRequest request,
            HttpServletRequest httpRequest) {
        return vmRequestService.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping
    public PageResponse<VmRequestDetailResponse> listVmRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) VmRequestStatus status,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return vmRequestService.list(principal, status, groupId, page, size);
    }

    @GetMapping("/{requestId}")
    public VmRequestDetailResponse getVmRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId) {
        return vmRequestService.get(principal, requestId);
    }

    @PostMapping("/{requestId}/cancel")
    public VmRequestDetailResponse cancelVmRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId,
            HttpServletRequest httpRequest) {
        return vmRequestService.cancel(principal, requestId, clientIp(httpRequest));
    }
}
