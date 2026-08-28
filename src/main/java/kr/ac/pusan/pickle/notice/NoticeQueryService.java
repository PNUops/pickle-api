package kr.ac.pusan.pickle.notice;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notice.dto.AdminNoticeView;
import kr.ac.pusan.pickle.notice.dto.NoticeView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notice reads.
 *
 * <p>The three public ones are served without authentication, so the principal
 * is nullable and decides the whole of what comes back: an anonymous caller
 * sees the notices published PUBLIC, a signed-in one sees every notice inside
 * its window. Since V95 that is the entire axis — an organisation names who
 * supplies a resource, not who may read a notice — so being signed in is the
 * only thing the audience axis distinguishes. A notice the caller may not see
 * answers 404 rather than 403, because a refusal would confirm that the
 * identifier names a real notice, which is the thing the audience axis exists
 * to keep private.</p>
 *
 * <p>The admin read is the same rows without the window filter, so scheduled
 * and expired notices are manageable; each row says whether it is
 * {@code active} right now.</p>
 */
@Service
public class NoticeQueryService {

    private final NoticeRepository noticeRepository;
    private final NoticeImageStore noticeImageStore;
    private final UserRepository userRepository;

    public NoticeQueryService(NoticeRepository noticeRepository,
            NoticeImageStore noticeImageStore, UserRepository userRepository) {
        this.noticeRepository = noticeRepository;
        this.noticeImageStore = noticeImageStore;
        this.userRepository = userRepository;
    }

    /** Contract {@code listNotices}: the active window, pinned first, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<NoticeView> list(@Nullable AuthenticatedUser reader, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Notice> result = reader == null
                ? noticeRepository.findVisibleToAnonymous(now, pageRequest)
                : noticeRepository.findVisibleToSignedIn(now, pageRequest);
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> NoticeView.from(notice,
                        images.getOrDefault(notice.getId(), List.of())))
                .toList(), result);
    }

    /** Contract {@code getNotice}: one notice, 404 when the caller may not see it. */
    @Transactional(readOnly = true)
    public NoticeView get(@Nullable AuthenticatedUser reader, UUID noticeId) {
        Notice notice = readable(reader, noticeId);
        return NoticeView.from(notice, noticeImageStore.metadataByNotice(List.of(notice.getId()))
                .getOrDefault(notice.getId(), List.of()));
    }

    /**
     * Contract {@code getNoticeImage}: the stored bytes. The image is reached
     * through its notice, so it inherits that notice's visibility — an image of
     * a notice the caller may not see is as absent as the notice, 404 rather
     * than 403 like everything else here.
     *
     * <p>With one widening the JSON reads do not need: a caller who may
     * <em>manage</em> the notice also gets its images outside the active
     * window. Without it the management screen cannot show a scheduled notice
     * the moment before publishing it, or an expired one being re-dated — its
     * own list row carries the image URLs, and every one of them would 404. The
     * body of those notices already reaches the same people through
     * {@link #listForAdmin}, so this widens no information, only the path it
     * arrives by.</p>
     */
    @Transactional(readOnly = true)
    public NoticeImageDelivery image(@Nullable AuthenticatedUser reader, UUID noticeId,
            UUID imageId) {
        Instant now = Instant.now();
        Notice notice = noticeRepository.findByPublicId(noticeId)
                .filter(candidate -> visibleTo(candidate, reader, now)
                        || manageableBy(reader))
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
        NoticeImageContent content = noticeImageStore.load(notice.getId(), imageId)
                .orElseThrow(() -> notFound("해당 이미지가 존재하지 않습니다."));
        // A shared cache may keep this only if an anonymous request for the same
        // URL would succeed right now — which is precisely what the visibility
        // rule answers with no reader. Deriving the directive from the rule
        // rather than restating it means the two cannot drift, so the moment a
        // notice stops being anonymously readable its images stop being MARKED
        // shareable, including a PUBLIC one that has not started yet. Asking
        // audience == PUBLIC here instead would look equivalent and drop the
        // window half of the rule.
        // What that does not do is reach copies a shared cache already stored:
        // those keep being served until they expire, which is the whole reason
        // the shared ceiling is an hour rather than a year (NoticeController).
        return new NoticeImageDelivery(content.contentType(), content.bytes(),
                visibleTo(notice, null, now));
    }

    /**
     * Contract {@code listAdminNotices}: every notice, window or not. Since V95
     * there is nothing left to scope — a notice belongs to no organisation, so
     * every role the controller's gate admits reads the same list, and what
     * separates the roles is only whether they may write (enforced in
     * {@link NoticeService}).
     *
     * <p>That includes an org-tier account granted no organisation at all,
     * which this used to refuse. Refusing it was the organisation deciding who
     * may use a feature, which is what V95 removed.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNoticeView> listForAdmin(int page, int size) {
        Instant now = Instant.now();
        Page<Notice> result = noticeRepository.findAllForAdmin(PageRequest.of(page, size));
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        Map<Long, String> authors = authorNamesOf(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> AdminNoticeView.from(notice, authors.get(notice.getCreatedBy()),
                        images.getOrDefault(notice.getId(), List.of()), now))
                .toList(), result);
    }

    /** The notice behind a public id, or 404 when this reader may not see it. */
    private Notice readable(@Nullable AuthenticatedUser reader, UUID noticeId) {
        Instant now = Instant.now();
        return noticeRepository.findByPublicId(noticeId)
                .filter(candidate -> visibleTo(candidate, reader, now))
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
    }

    /**
     * The single statement of who may read a notice, and the mirror of
     * {@link NoticeRepository#findVisibleToAnonymous}. Outside the active
     * window nobody gets it through this surface; inside it, a signed-in caller
     * gets it whatever its audience, and an anonymous one only if it is PUBLIC.
     *
     * <p>Both halves matter, and the window is the half that is easy to lose:
     * {@link #image} derives its cache directive by calling this with no
     * reader, so restating it as {@code audience == PUBLIC} anywhere would mark
     * a scheduled notice's images shareable before it is published.</p>
     */
    static boolean visibleTo(Notice notice, @Nullable AuthenticatedUser reader, Instant now) {
        return notice.isActiveAt(now)
                && (reader != null || notice.getAudience() == NoticeAudience.PUBLIC);
    }

    /**
     * Whether this caller may manage notices — the mirror of the class-level
     * gate on {@link AdminNoticeController}, restated here because
     * {@code getNoticeImage} is served without authentication and so carries no
     * {@code @PreAuthorize} to have narrowed the roles first.
     *
     * <p>It asks nothing about the notice because there is nothing to ask:
     * every account that reaches the management list reaches every row of it.</p>
     */
    private static boolean manageableBy(@Nullable AuthenticatedUser reader) {
        return reader != null && (reader.role().isSysTier() || reader.role().isOrgTier());
    }

    private Map<Long, List<NoticeImageMeta>> imagesOf(List<Notice> notices) {
        return noticeImageStore.metadataByNotice(notices.stream().map(Notice::getId).toList());
    }

    /** Author display names for the admin list, batched into one read. */
    private Map<Long, String> authorNamesOf(List<Notice> notices) {
        List<Long> ids = notices.stream().map(Notice::getCreatedBy).filter(Objects::nonNull)
                .distinct().toList();
        return ids.isEmpty() ? new HashMap<>()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a,
                                HashMap::new));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
