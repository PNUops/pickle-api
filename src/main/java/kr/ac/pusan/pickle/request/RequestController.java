package kr.ac.pusan.pickle.request;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
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

/** Contract tag {@code requests} (server /api/v1). */
@RestController
@RequestMapping("/api/v1/requests")
public class RequestController {

    private final RequestService requestService;

    public RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestDetailResponse createRequest(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRequestRequest request,
            HttpServletRequest httpRequest) {
        return requestService.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping
    public PageResponse<RequestDetailResponse> listRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) ResourceType type,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return requestService.list(principal, status, type, workspaceId, page, size);
    }

    @GetMapping("/{requestId}")
    public RequestDetailResponse getRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId) {
        return requestService.get(principal, requestId);
    }

    @PostMapping("/{requestId}/cancel")
    public RequestDetailResponse cancelRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId,
            HttpServletRequest httpRequest) {
        return requestService.cancel(principal, requestId, clientIp(httpRequest));
    }
}
