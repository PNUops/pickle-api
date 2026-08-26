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
     * <p>The identifier is a UUID naming one immutable set of bytes, so a
     * changed image is a different URL and a stale entry is impossible. What
     * can go stale is not the bytes but the <em>entitlement</em>: a notice can
     * flip from PUBLIC to USERS, reach the end of its window, or be deleted.
     * {@code immutable} tells a cache it never needs to revalidate, which is
     * precisely the promise that cannot be kept once entitlement can change, so
     * the shared branch does not make it.</p>
     *
     * <p>{@code public} is the directive that lets a cache shared between users
     * store a response to a request that carried an {@code Authorization}
     * header (RFC 9111), and this response has no {@code Vary} that would
     * separate one caller from another — so on an image whose visibility
     * depends on who asked, {@code public} would turn a per-request check into
     * "whoever has the URL". It is therefore sent only when an anonymous
     * request for this same URL would succeed anyway; everything else gets
     * {@code private}, which still caches in the requester's own browser and
     * costs nothing.</p>
     *
     * <p>The two lifetimes differ because the two blast radii do. Only a
     * <b>shared</b> cache can serve a revoked image to somebody who was never
     * entitled to it, so {@code s-maxage} bounds that to an hour. A private
     * cache can only re-serve bytes to the one requester who already received
     * them legitimately, which is indistinguishable from their having saved the
     * file, so the year stays there and buys the repeat views it was for.</p>
     *
     * <p>{@code inline} because these are body illustrations to render, not
     * files to save.</p>
     */
    @GetMapping("/{noticeId}/images/{imageId}")
    public ResponseEntity<byte[]> getNoticeImage(
            @AuthenticationPrincipal @Nullable AuthenticatedUser principal,
            @PathVariable UUID noticeId, @PathVariable UUID imageId) {
        NoticeImageDelivery image = noticeQueryService.image(principal, noticeId, imageId);
        String cacheControl = image.sharedCacheable()
                ? "public, max-age=31536000, s-maxage=3600"
                : "private, max-age=31536000, immutable";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.bytes());
    }
}
