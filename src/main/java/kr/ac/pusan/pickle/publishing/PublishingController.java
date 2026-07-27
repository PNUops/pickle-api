package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainSummaryView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.publishing.dto.PublishRequest;
import kr.ac.pusan.pickle.publishing.dto.UpdatePublicationRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
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
import tools.jackson.databind.JsonNode;

/**
 * Contract tag {@code publishing} (openapi.yaml v0.4.0, server /api/v1): VM HTTP
 * service publish lifecycle and self-service domain management. All mutating ops
 * write intent and return 202 (async apply); reads return 200.
 */
@RestController
@RequestMapping("/api/v1")
public class PublishingController {

    private final PublishingService publishingService;

    public PublishingController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @PostMapping("/vms/{vmId}/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicationView publishVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @RequestBody(required = false) PublishRequest request, HttpServletRequest httpRequest) {
        PublishRequest body = request != null ? request : new PublishRequest(null, null, null, null);
        return publishingService.publish(principal, vmId,
                body.port(), body.subdomain(), body.rootDomain(), body.customDomain(),
                clientIp(httpRequest));
    }

    /**
     * PATCH binds the raw body so an omitted {@code customDomain} (leave the
     * domain unchanged, port-only edit) is distinguishable from an explicit
     * {@code null} (detach the custom domain, revert to the platform subdomain).
     */
    @PatchMapping("/vms/{vmId}/publication")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            schema = @Schema(implementation = UpdatePublicationRequest.class)))
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicationView updatePublication(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @RequestBody JsonNode body, HttpServletRequest httpRequest) {
        Integer port = null;
        if (body.has("port")) {
            JsonNode portNode = body.get("port");
            port = portNode.isNumber() ? portNode.asInt() : -1; // non-numeric → 422 range
        }
        boolean customProvided = body.has("customDomain");
        String customDomain = customProvided && !body.get("customDomain").isNull()
                ? body.get("customDomain").asString() : null;
        return publishingService.updatePublication(principal, vmId,
                port, customProvided, customDomain, clientIp(httpRequest));
    }

    @DeleteMapping("/vms/{vmId}/publication")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse unpublishVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        return publishingService.unpublish(principal, vmId, clientIp(httpRequest));
    }

    @GetMapping("/domains")
    public PageResponse<DomainSummaryView> listDomains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long vmId,
            @RequestParam(required = false) DomainStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return publishingService.listDomains(principal, vmId, status, page, size);
    }

    @GetMapping("/domains/{domainId}")
    public DomainDetailView getDomain(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long domainId) {
        return publishingService.getDomain(principal, domainId);
    }

    @DeleteMapping("/domains/{domainId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse deleteDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long domainId,
            HttpServletRequest httpRequest) {
        return publishingService.deleteDomain(principal, domainId, clientIp(httpRequest));
    }

    @PostMapping("/domains/{domainId}/verify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DomainDetailView verifyDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long domainId,
            HttpServletRequest httpRequest) {
        return publishingService.verifyDomain(principal, domainId, clientIp(httpRequest));
    }
}
