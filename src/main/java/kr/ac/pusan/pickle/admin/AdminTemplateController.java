package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminTemplateResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateTemplateStatusRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, template inventory (v0.21.0) — the sys tier
 * reads every revision (the public {@code GET /templates} shows ACTIVE only);
 * the status toggle, being an operational-state write, is SYS_ADMIN-only.
 * Small reference list, plain array per the contract convention.
 */
@RestController
@RequestMapping("/api/v1/admin/templates")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminTemplateController {

    private final AdminInventoryService adminInventoryService;

    public AdminTemplateController(AdminInventoryService adminInventoryService) {
        this.adminInventoryService = adminInventoryService;
    }

    @GetMapping
    public List<AdminTemplateResponse> listAdminTemplates() {
        return adminInventoryService.listTemplates();
    }

    @PatchMapping("/{templateId}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public AdminTemplateResponse updateAdminTemplate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long templateId,
            @Valid @RequestBody UpdateTemplateStatusRequest request,
            HttpServletRequest httpRequest) {
        return adminInventoryService.updateTemplateStatus(principal, templateId, request,
                clientIp(httpRequest));
    }
}
