package kr.ac.pusan.pickle.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.dto.AuditLogViewResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code listAuditLogs} (tag {@code admin}). <b>The narrowest admin
 * read.</b> The org tier sees rows whose actor belongs to an organisation it may
 * <b>act</b> in — a read-only role does not reach this surface at all, because
 * the rows carry login addresses and those are evidence rather than operational
 * state. Enforced in SQL; naming any other organisation answers 404. The sys
 * tier sees everything with the optional org filter.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    public AdminAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AuditLogViewResponse> listAuditLogs(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return auditQueryService.adminAudit(principal, actorEmail, action, targetType, targetId,
                from, to, orgId, page, size);
    }
}
