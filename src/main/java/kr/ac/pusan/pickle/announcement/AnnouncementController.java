package kr.ac.pusan.pickle.announcement;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementCreateRequest;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementPreviewResponse;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementDetailResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;

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

    @PostMapping("/preview")
    @Operation(operationId = "previewAnnouncement", summary = "알림 발송 대상 미리보기",
            description = "현재 활성 대상자의 예상 인원과 최대 20명 예시를 조회합니다. 발송은 생성 요청 시 다시 대상을 선정하며 이 응답은 발송 예약이 아닙니다.")
    public AnnouncementPreviewResponse previewAnnouncement(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AnnouncementCreateRequest request) {
        return announcementService.preview(principal, request);
    }

    @GetMapping("/{announcementId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(operationId = "getAnnouncement", summary = "알림 발송건 상세 조회",
            description = "저장된 내용과 대상, 발송 당시 선정 인원을 조회합니다. 기관 계층의 열람 범위는 발송 목록과 같은 작성자 현재 기관 역할 기준입니다.")
    public AnnouncementDetailResponse getAnnouncement(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID announcementId) {
        return announcementQueryService.get(principal, announcementId);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AnnouncementView> listAnnouncements(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return announcementQueryService.list(principal, page, size);
    }
}
