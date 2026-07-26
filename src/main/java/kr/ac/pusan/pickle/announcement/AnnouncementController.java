package kr.ac.pusan.pickle.announcement;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementCreateRequest;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementView;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, announcements ({@code createAnnouncement} /
 * {@code listAnnouncements}). ORG_ADMIN and SYS_ADMIN; the ALL-scope
 * SYS_ADMIN-only rule is enforced in the service (403).
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final AnnouncementQueryService announcementQueryService;

    public AnnouncementController(AnnouncementService announcementService,
            AnnouncementQueryService announcementQueryService) {
        this.announcementService = announcementService;
        this.announcementQueryService = announcementQueryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementView createAnnouncement(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AnnouncementCreateRequest request,
            HttpServletRequest httpRequest) {
        return announcementService.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    public PageResponse<AnnouncementView> listAnnouncements(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return announcementQueryService.list(principal, page, size);
    }
}
