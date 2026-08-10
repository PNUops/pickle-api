package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.CreateVmFlavorRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateVmFlavorRequest;
import kr.ac.pusan.pickle.inventory.dto.VmFlavorResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, flavor catalog (v0.23.0 axis split) — the sys
 * tier reads every preset (the public {@code GET /vm-flavors} shows ACTIVE
 * only); create/edit are operational-state writes, SYS_ADMIN-only. Same
 * gates and list convention as the OS image inventory.
 */
@RestController
@RequestMapping("/api/v1/admin/vm-flavors")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminVmFlavorController {

    private final AdminInventoryService adminInventoryService;

    public AdminVmFlavorController(AdminInventoryService adminInventoryService) {
        this.adminInventoryService = adminInventoryService;
    }

    @GetMapping
    public List<VmFlavorResponse> listAdminVmFlavors() {
        return adminInventoryService.listFlavors();
    }

    @PostMapping
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public VmFlavorResponse createAdminVmFlavor(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateVmFlavorRequest request,
            HttpServletRequest httpRequest) {
        return adminInventoryService.createFlavor(principal, request, clientIp(httpRequest));
    }

    @PatchMapping("/{flavorId}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public VmFlavorResponse updateAdminVmFlavor(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID flavorId,
            @Valid @RequestBody UpdateVmFlavorRequest request,
            HttpServletRequest httpRequest) {
        return adminInventoryService.updateFlavor(principal, flavorId, request, clientIp(httpRequest));
    }
}
