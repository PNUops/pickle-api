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
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
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
 * sees platform notices published PUBLIC, a signed-in one additionally sees
 * every platform notice and their own organisation's. A notice the caller may
 * not see answers 404 rather than 403 — a refusal would confirm that a
 * particular organisation published a particular notice, which is the thing the
 * audience axis exists to keep private.</p>
 *
 * <p>The admin read is the same rows without the window filter, so scheduled
 * and expired notices are manageable; each row says whether it is
 * {@code active} right now.</p>
 */
@Service
public class NoticeQueryService {

    private final NoticeRepository noticeRepository;
    private final NoticeImageStore noticeImageStore;
    private final OrgRepository orgRepository;

    public NoticeQueryService(NoticeRepository noticeRepository,
            NoticeImageStore noticeImageStore, OrgRepository orgRepository) {
        this.noticeRepository = noticeRepository;
        this.noticeImageStore = noticeImageStore;
        this.orgRepository = orgRepository;
    }

    /** Contract {@code listNotices}: the active window, pinned first, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<NoticeView> list(@Nullable AuthenticatedUser reader, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Notice> result = reader == null
                ? noticeRepository.findVisibleToAnonymous(now, pageRequest)
                : noticeRepository.findVisibleToUser(now, reader.orgId(), pageRequest);
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        Map<Long, UUID> orgIds = orgPublicIds(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> NoticeView.from(notice, orgIds.get(notice.getOrgId()),
                        images.getOrDefault(notice.getId(), List.of())))
                .toList(), result);
    }

    /** Contract {@code getNotice}: one notice, 404 when the caller may not see it. */
    @Transactional(readOnly = true)
    public NoticeView get(@Nullable AuthenticatedUser reader, UUID noticeId) {
        Notice notice = readable(reader, noticeId);
        return NoticeView.from(notice, orgPublicIds(List.of(notice)).get(notice.getOrgId()),
                noticeImageStore.metadataByNotice(List.of(notice.getId()))
                        .getOrDefault(notice.getId(), List.of()));
    }

    /**
     * Contract {@code getNoticeImage}: the stored bytes. The image is reached
     * through its notice, so it inherits that notice's visibility exactly — an
     * image of a notice the caller may not see is as absent as the notice.
     */
    @Transactional(readOnly = true)
    public NoticeImageContent image(@Nullable AuthenticatedUser reader, UUID noticeId,
            UUID imageId) {
        Notice notice = readable(reader, noticeId);
        return noticeImageStore.load(notice.getId(), imageId)
                .orElseThrow(() -> notFound("해당 이미지가 존재하지 않습니다."));
    }

    /**
     * Contract {@code listAdminNotices}: every notice the caller administers,
     * window or not. The org tier additionally sees the platform's notices,
     * which it reads but cannot write ({@link NoticeService} enforces that).
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNoticeView> listForAdmin(AuthenticatedUser actor, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Notice> result;
        if (actor.role().isSysTier()) {
            result = noticeRepository.findAllForAdmin(pageRequest);
        } else {
            if (actor.orgId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
            }
            result = noticeRepository.findForOrgAdmin(actor.orgId(), pageRequest);
        }
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        Map<Long, UUID> orgIds = orgPublicIds(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> AdminNoticeView.from(notice, orgIds.get(notice.getOrgId()),
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
     * The single statement of who may read a notice. Anonymous callers get
     * platform notices published PUBLIC; a signed-in caller gets every platform
     * notice plus their own organisation's. Outside the active window nobody
     * gets any of them through this surface.
     *
     * <p>"Their own organisation" is read from the account's {@code org_id}
     * column, which the schema sets for the administrator and manager tiers
     * only — an ordinary account's organisation is derived from the resources
     * of the workspaces it belongs to. An ORG notice therefore reaches that
     * organisation's administrators rather than everyone working under it. If
     * the wider set is what a notice should reach, this method and
     * {@link NoticeRepository#findVisibleToUser} are the two places that say
     * so.</p>
     */
    static boolean visibleTo(Notice notice, @Nullable AuthenticatedUser reader, Instant now) {
        if (!notice.isActiveAt(now)) {
            return false;
        }
        if (notice.getScope() == NoticeScope.PLATFORM) {
            return reader != null || notice.getAudience() == NoticeAudience.PUBLIC;
        }
        // An ORG notice is USERS-only by constraint, so a null reader never
        // reaches here with a match; equals keeps the comparison a value one.
        return reader != null && notice.getOrgId().equals(reader.orgId());
    }

    private Map<Long, List<NoticeImageMeta>> imagesOf(List<Notice> notices) {
        return noticeImageStore.metadataByNotice(notices.stream().map(Notice::getId).toList());
    }

    /**
     * Org rows behind the page's {@code org_id}s, batched into one read. The
     * result is a HashMap on purpose: callers look a platform notice's null
     * {@code orgId} up in it, and {@code Map.of()} throws on a null key.
     */
    private Map<Long, UUID> orgPublicIds(List<Notice> notices) {
        List<Long> ids = notices.stream().map(Notice::getOrgId).filter(Objects::nonNull)
                .distinct().toList();
        return ids.isEmpty() ? new HashMap<>()
                : orgRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Org::getId, Org::getPublicId, (a, b) -> a,
                                HashMap::new));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
