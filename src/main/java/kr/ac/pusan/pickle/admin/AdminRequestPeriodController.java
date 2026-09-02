package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminRequestPeriodResponse;
import kr.ac.pusan.pickle.admin.dto.CreateRequestPeriodRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateRequestPeriodRequest;
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
 * Contract tag {@code admin}, usage-period catalogue — the sys tier reads every
 * period including the expired ones (the public {@code GET /request-periods}
 * shows only what a request could still sit inside); create and edit are
 * operational-state writes, SYS_ADMIN-only. Same gates and list convention as
 * the spec catalogue.
 */
@RestController
@RequestMapping("/api/v1/admin/request-periods")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminRequestPeriodController {

    private final AdminRequestPeriodService service;

    public AdminRequestPeriodController(AdminRequestPeriodService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public List<AdminRequestPeriodResponse> listAdminRequestPeriods() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRequestPeriodResponse createAdminRequestPeriod(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRequestPeriodRequest request,
            HttpServletRequest httpRequest) {
        return service.create(principal, request, clientIp(httpRequest));
    }

    @PatchMapping("/{periodId}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public AdminRequestPeriodResponse updateAdminRequestPeriod(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID periodId,
            @Valid @RequestBody UpdateRequestPeriodRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, periodId, request, clientIp(httpRequest));
    }
}
