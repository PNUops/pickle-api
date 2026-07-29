package kr.ac.pusan.pickle.relay;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.relay.dto.AdminPortMappingResponse;
import kr.ac.pusan.pickle.relay.dto.SuspendPortMappingRequest;
import kr.ac.pusan.pickle.relay.dto.UpdatePortMappingGuardsRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, port-mapping intervention: list (SYS tier),
 * suspend/unsuspend/delete (SYS tier), per-mapping guard overrides
 * (SYS_ADMIN only — they widen or narrow the abuse defense itself).
 */
@RestController
@RequestMapping("/api/v1/admin/port-mappings")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminPortMappingController {

    private final AdminPortMappingService adminPortMappingService;

    public AdminPortMappingController(AdminPortMappingService adminPortMappingService) {
        this.adminPortMappingService = adminPortMappingService;
    }

    @GetMapping
    public PageResponse<AdminPortMappingResponse> listAdminPortMappings(
            @RequestParam(required = false) Long relayId,
            @RequestParam(required = false) Long vmId,
            @RequestParam(required = false) PortMappingStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPortMappingService.list(relayId, vmId, status, page, size);
    }

    /** 200 with the updated row (contract): convergence itself stays async. */
    @PostMapping("/{mappingId}/suspend")
    public AdminPortMappingResponse suspendAdminPortMapping(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long mappingId,
            @Valid @RequestBody SuspendPortMappingRequest request,
            HttpServletRequest httpRequest) {
        return adminPortMappingService.suspend(principal, mappingId, request.reason().strip(),
                clientIp(httpRequest));
    }

    @PostMapping("/{mappingId}/unsuspend")
    public AdminPortMappingResponse unsuspendAdminPortMapping(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long mappingId,
            HttpServletRequest httpRequest) {
        return adminPortMappingService.unsuspend(principal, mappingId, clientIp(httpRequest));
    }

    @DeleteMapping("/{mappingId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse deleteAdminPortMapping(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long mappingId,
            HttpServletRequest httpRequest) {
        return adminPortMappingService.delete(principal, mappingId, clientIp(httpRequest));
    }

    /**
     * PATCH binds the raw body so an omitted guard field (keep the current
     * value) is distinguishable from an explicit {@code null} (clear to the
     * agent default).
     */
    @PatchMapping("/{mappingId}/guards")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            schema = @Schema(implementation = UpdatePortMappingGuardsRequest.class)))
    public AdminPortMappingResponse updateAdminPortMappingGuards(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long mappingId,
            @RequestBody tools.jackson.databind.JsonNode body, HttpServletRequest httpRequest) {
        return adminPortMappingService.updateGuards(principal, mappingId, body,
                clientIp(httpRequest));
    }
}
