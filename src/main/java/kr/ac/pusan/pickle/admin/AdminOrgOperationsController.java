package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import kr.ac.pusan.pickle.admin.dto.SaveOrgOperationsRequest;
import kr.ac.pusan.pickle.admin.dto.OrgOperationsPreviewResponse;
import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/operations")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminOrgOperationsController {

    private final AdminOrgOperationsQueryService queryService;
    private final AdminOrgOperationsService service;

    public AdminOrgOperationsController(AdminOrgOperationsQueryService queryService, AdminOrgOperationsService service) {
        this.queryService = queryService;
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getAdminOrgOperations", summary = "기관 역할 명단과 현재 신청 메일 수신자 조회")
    public AdminOrgOperationsResponse getAdminOrgOperations(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID orgId,
            @RequestParam(required = false) @Nullable UUID requesterId) {
        return queryService.get(actor, orgId, requesterId);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @Operation(operationId = "previewAdminOrgOperations", summary = "기관 명단과 수신 설정의 변경안 미리보기")
    public OrgOperationsPreviewResponse previewAdminOrgOperations(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID orgId,
            @Valid @RequestBody SaveOrgOperationsRequest request) {
        return service.preview(actor, orgId, request);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @Operation(operationId = "saveAdminOrgOperations", summary = "기관 명단과 수신 설정의 원자 저장")
    public AdminOrgOperationsResponse saveAdminOrgOperations(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID orgId,
            @Valid @RequestBody SaveOrgOperationsRequest request, HttpServletRequest httpRequest) {
        return service.save(actor, orgId, request, clientIp(httpRequest));
    }
}
