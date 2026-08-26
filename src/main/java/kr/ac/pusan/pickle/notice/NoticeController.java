package kr.ac.pusan.pickle.notice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notice.dto.NoticeView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code notices} — the public notice board ({@code listNotices},
 * {@code getNotice}, {@code getNoticeImage}).
 *
 * <p>Served without authentication, which is the point: the landing page shows
 * notices before anyone has an account, and a maintenance window is exactly
 * when a notice most needs to be readable. There is deliberately no
 * {@code @PreAuthorize} anywhere here — the principal is an <em>input</em>
 * rather than a gate, and it is nullable because
 * {@code JwtAuthenticationFilter} populates it on permitAll paths too. What the
 * caller may see is decided in {@link NoticeQueryService}, and what they may
 * not is 404, never 403.</p>
 */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

    private final NoticeQueryService noticeQueryService;

    public NoticeController(NoticeQueryService noticeQueryService) {
        this.noticeQueryService = noticeQueryService;
    }

    @GetMapping
    public PageResponse<NoticeView> listNotices(
            @AuthenticationPrincipal @Nullable AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return noticeQueryService.list(principal, page, size);
    }

    @GetMapping("/{noticeId}")
    public NoticeView getNotice(@AuthenticationPrincipal @Nullable AuthenticatedUser principal,
            @PathVariable UUID noticeId) {
        return noticeQueryService.get(principal, noticeId);
    }

    /**
     * The stored bytes, under the type they were determined to be at upload.
     *
     * <p>Cached hard and forever: the identifier is a UUID that names one
     * immutable set of bytes, so a changed image is a different URL and a stale
     * cache entry is impossible. {@code inline} because these are body
     * illustrations to render, not files to save.</p>
     */
    @GetMapping("/{noticeId}/images/{imageId}")
    public ResponseEntity<byte[]> getNoticeImage(
            @AuthenticationPrincipal @Nullable AuthenticatedUser principal,
            @PathVariable UUID noticeId, @PathVariable UUID imageId) {
        NoticeImageContent image = noticeQueryService.image(principal, noticeId, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.bytes());
    }
}
