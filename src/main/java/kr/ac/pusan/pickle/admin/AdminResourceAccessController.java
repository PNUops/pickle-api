package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessGrantService;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact;
import kr.ac.pusan.pickle.access.dto.ResourceAccessChangePreviewRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessGrantView;
import kr.ac.pusan.pickle.access.dto.ResourceAccessListResponse;
import kr.ac.pusan.pickle.access.dto.UpdateResourceAccessGrantRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Support entry points share the ordinary grant domain and its four adapters. */
@RestController
@RequestMapping("/api/v1/admin/resource-access/{resourceType}/{resourceId}")
public class AdminResourceAccessController {
    private final ResourceAccessGrantService service;
    public AdminResourceAccessController(ResourceAccessGrantService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_VIEWER','SYS_MANAGER','SYS_ADMIN')")
    public ResourceAccessListResponse getAdminResourceAccess(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId) {
        return service.list(actor, resourceType, resourceId, true);
    }
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public ActorAccessImpact previewAdminResourceAccessChange(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId,
            @Valid @RequestBody ResourceAccessChangePreviewRequest request) {
        return service.preview(actor, resourceType, resourceId, request, true);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public ResourceAccessGrantView addAdminResourceAccessGrant(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId,
            @Valid @RequestBody AddResourceAccessGrantRequest request, HttpServletRequest http) {
        return service.add(actor, resourceType, resourceId, request, clientIp(http), true);
    }
    @PatchMapping("/{grantId}")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public ResourceAccessGrantView updateAdminResourceAccessGrant(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId, @PathVariable UUID grantId,
            @Valid @RequestBody UpdateResourceAccessGrantRequest request, HttpServletRequest http) {
        return service.update(actor, resourceType, resourceId, grantId, request, clientIp(http), true);
    }
    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public void removeAdminResourceAccessGrant(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId, @PathVariable UUID grantId,
            HttpServletRequest http) {
        service.remove(actor, resourceType, resourceId, grantId, clientIp(http), true);
    }
}
