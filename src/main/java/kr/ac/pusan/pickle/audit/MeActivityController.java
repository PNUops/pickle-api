package kr.ac.pusan.pickle.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import kr.ac.pusan.pickle.audit.dto.ActivityEntryResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code listMyActivity} (tag {@code me}): strictly the caller's own
 * audit rows (login history included) — no filter can widen the scope.
 */
@RestController
@RequestMapping("/api/v1/me/activity")
public class MeActivityController {

    private final AuditQueryService auditQueryService;

    public MeActivityController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public PageResponse<ActivityEntryResponse> listMyActivity(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return auditQueryService.myActivity(principal.id(), action, from, to, page, size);
    }
}
