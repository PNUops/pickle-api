package kr.ac.pusan.pickle.notice;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notice.dto.AdminNoticeView;
import kr.ac.pusan.pickle.notice.dto.NoticeCreateRequest;
import kr.ac.pusan.pickle.notice.dto.NoticeImageView;
import kr.ac.pusan.pickle.notice.dto.NoticeUpdateRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Contract tag {@code admin}, notice management.
 *
 * <p>Every role above USER reads the list; only the two admin roles write. The
 * viewer roles are in because a viewer holds whatever read access its tier's
 * operator holds, and a notice carries nothing that argues otherwise. The one
 * surface that does argue otherwise is the audit log, and only for the org
 * viewer: its rows carry login addresses, so within the org tier it stays with
 * the roles that may act. SYS_VIEWER reads it like every other system read.</p>
 *
 * <p>Reading the list is not the whole of what that grants. The same scope
 * reaches the images of those notices while they are outside their active
 * window, through {@code NoticeQueryService.manageableBy} — the list row
 * carries the image URLs, so a management view whose every image 404s is not
 * one. A viewer therefore sees exactly what an operator of the same
 * organisation sees, on both surfaces.</p>
 *
 * <p>Each write carries its own {@code @PreAuthorize}, which fully replaces
 * this class-level one, so widening the read gate cannot reach them.</p>
 *
 * <p>The org-scoping the matrix calls {@code allow_org_scoped} is service-layer
 * and cannot be said in an annotation: an org-tier list includes the platform's
 * notices as read-only rows, and every write against one of those is refused
 * in {@link NoticeService}.</p>
 *
 * <p>There is no admin detail read on purpose — {@link AdminNoticeView} rows
 * carry the body, so the list is already the editor's source.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/notices")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminNoticeController {

    private final NoticeService noticeService;
    private final NoticeQueryService noticeQueryService;

    public AdminNoticeController(NoticeService noticeService,
            NoticeQueryService noticeQueryService) {
        this.noticeService = noticeService;
        this.noticeQueryService = noticeQueryService;
    }

    @GetMapping
    public PageResponse<AdminNoticeView> listAdminNotices(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return noticeQueryService.listForAdmin(principal, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminNoticeView createAdminNotice(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody NoticeCreateRequest request,
            HttpServletRequest httpRequest) {
        return noticeService.create(principal, request, clientIp(httpRequest));
    }

    @PatchMapping("/{noticeId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    public AdminNoticeView updateAdminNotice(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID noticeId,
            @Valid @RequestBody NoticeUpdateRequest request,
            HttpServletRequest httpRequest) {
        return noticeService.update(principal, noticeId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{noticeId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminNotice(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID noticeId, HttpServletRequest httpRequest) {
        noticeService.delete(principal, noticeId, clientIp(httpRequest));
    }

    /**
     * Attaches one image to the notice body. The part is optional at the
     * binding layer so a request without it answers the ordinary 422 rather
     * than a framework 500.
     */
    @PostMapping(value = "/{noticeId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public NoticeImageView uploadAdminNoticeImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID noticeId,
            @RequestPart(value = "file", required = false) @Nullable MultipartFile file,
            HttpServletRequest httpRequest) {
        return noticeService.addImage(principal, noticeId, file, clientIp(httpRequest));
    }

    @DeleteMapping("/{noticeId}/images/{imageId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminNoticeImage(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID noticeId, @PathVariable UUID imageId,
            HttpServletRequest httpRequest) {
        noticeService.deleteImage(principal, noticeId, imageId, clientIp(httpRequest));
    }
}
