package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.CreateVmDomainRequest;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainSummaryView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRequest;
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

/**
 * Contract tag {@code publishing} (server /api/v1): self-service domain
 * management for a VM's HTTP service. A VM may carry several domains at once
 * (contract v0.29.0), each with its own port; the domain is the unit every
 * mutating endpoint works on. All mutating ops write intent and return 202
 * (async apply); reads return 200.
 */
@RestController
@RequestMapping("/api/v1")
public class PublishingController {

    private final PublishingService publishingService;

    public PublishingController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @PostMapping("/vms/{vmId}/domains")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicationView createVmDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vmId,
            @RequestBody(required = false) CreateVmDomainRequest request,
            HttpServletRequest httpRequest) {
        CreateVmDomainRequest body = request != null ? request
                : new CreateVmDomainRequest(null, null, null, null);
        return publishingService.createDomain(principal, vmId,
                body.port(), body.subdomain(), body.rootDomain(), body.customDomain(),
                clientIp(httpRequest));
    }

    @PatchMapping("/domains/{domainId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicationView updateDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID domainId,
            @RequestBody UpdateDomainRequest request, HttpServletRequest httpRequest) {
        return publishingService.updateDomain(principal, domainId, request.port(),
                clientIp(httpRequest));
    }

    @GetMapping("/domains")
    public PageResponse<DomainSummaryView> listDomains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID vmId,
            @RequestParam(required = false) DomainStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return publishingService.listDomains(principal, vmId, status, page, size);
    }

    @GetMapping("/domains/{domainId}")
    public DomainDetailView getDomain(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return publishingService.getDomain(principal, domainId);
    }

    @DeleteMapping("/domains/{domainId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse deleteDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID domainId,
            HttpServletRequest httpRequest) {
        return publishingService.deleteDomain(principal, domainId, clientIp(httpRequest));
    }

    @PostMapping("/domains/{domainId}/verify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DomainDetailView verifyDomain(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID domainId,
            HttpServletRequest httpRequest) {
        return publishingService.verifyDomain(principal, domainId, clientIp(httpRequest));
    }
}
