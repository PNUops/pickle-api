package kr.ac.pusan.pickle.resource;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code resources} (server /api/v1). Read-only inventory. */
@RestController
@RequestMapping("/api/v1/resources")
public class ResourceIndexController {

    private final ResourceIndexService resourceIndexService;

    public ResourceIndexController(ResourceIndexService resourceIndexService) {
        this.resourceIndexService = resourceIndexService;
    }

    @GetMapping
    public PageResponse<ResourceSummaryResponse> listResources(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) ResourceType type,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return resourceIndexService.list(principal, type, workspaceId, page, size);
    }
}
