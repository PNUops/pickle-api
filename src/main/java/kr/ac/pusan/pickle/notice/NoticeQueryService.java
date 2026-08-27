package kr.ac.pusan.pickle.notice;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notice.dto.AdminNoticeView;
import kr.ac.pusan.pickle.notice.dto.NoticeView;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notice reads.
 *
 * <p>The three public ones are served without authentication, so the principal
 * is nullable and decides the whole of what comes back: an anonymous caller
 * sees platform notices published PUBLIC, a signed-in one additionally sees
 * every platform notice and the notices of the organisations they belong to. A
 * notice the caller may not see answers 404 rather than 403 — a refusal would
 * confirm that a particular organisation published a particular notice, which
 * is the thing the audience axis exists to keep private.</p>
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
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public NoticeQueryService(NoticeRepository noticeRepository,
            NoticeImageStore noticeImageStore, OrgRepository orgRepository,
            UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.noticeRepository = noticeRepository;
        this.noticeImageStore = noticeImageStore;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Contract {@code listNotices}: the active window, pinned first, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<NoticeView> list(@Nullable AuthenticatedUser reader, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Notice> result = pageFor(reader, now, pageRequest);
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        Map<Long, Org> orgs = orgsOf(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> NoticeView.from(notice, orgs.get(notice.getOrgId()),
                        images.getOrDefault(notice.getId(), List.of())))
                .toList(), result);
    }

    /** Contract {@code getNotice}: one notice, 404 when the caller may not see it. */
    @Transactional(readOnly = true)
    public NoticeView get(@Nullable AuthenticatedUser reader, UUID noticeId) {
        Notice notice = readable(reader, noticeId);
        return NoticeView.from(notice, orgsOf(List.of(notice)).get(notice.getOrgId()),
                noticeImageStore.metadataByNotice(List.of(notice.getId()))
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
        Set<Long> orgIds = reader == null ? Set.of() : readerOrgIds(reader);
        Notice notice = noticeRepository.findByPublicId(noticeId)
                .filter(candidate -> visibleTo(candidate, reader, orgIds, now)
                        || manageableBy(reader, candidate))
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
        NoticeImageContent content = noticeImageStore.load(notice.getId(), imageId)
                .orElseThrow(() -> notFound("해당 이미지가 존재하지 않습니다."));
        // A shared cache may keep this only if an anonymous request for the same
        // URL would succeed right now — which is precisely what the visibility
        // rule answers with no reader. Deriving the directive from the rule
        // rather than restating it means the two cannot drift, so the moment a
        // notice stops being anonymously readable its images stop being MARKED
        // shareable, including a PLATFORM+PUBLIC one that has not started yet.
        // What that does not do is reach copies a shared cache already stored:
        // those keep being served until they expire, which is the whole reason
        // the shared ceiling is an hour rather than a year (NoticeController).
        return new NoticeImageDelivery(content.contentType(), content.bytes(),
                visibleTo(notice, null, Set.of(), now));
    }

    /**
     * Contract {@code listAdminNotices}: every notice the caller administers,
     * window or not. The org tier additionally sees the platform's notices,
     * which it reads but cannot write ({@link NoticeService} enforces that).
     *
     * <p>Scoped by the organisations the account holds a role in ({@code
     * user_org_roles}, V90) rather than by the derived membership the reads use,
     * and deliberately: this asks who may <em>see the management view</em>, and
     * only the roles granted in an organisation reach it. It is the same split
     * the other admin surfaces make — the audit and user lists scope the actor
     * by their granted organisations and use the derived rule only for the rows
     * they are searching.</p>
     *
     * <p>The scope is {@link AuthenticatedUser#readableOrgIds()}, not the
     * operated or administered subset: a viewer role exists precisely to read
     * an organisation without acting in it, and every write is refused
     * separately in {@link NoticeService}.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminNoticeView> listForAdmin(AuthenticatedUser actor, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Notice> result;
        if (actor.role().isSysTier()) {
            result = noticeRepository.findAllForAdmin(pageRequest);
        } else {
            Set<Long> scope = actor.readableOrgIds();
            if (scope.isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
            }
            result = noticeRepository.findForOrgAdmin(scope, pageRequest);
        }
        Map<Long, List<NoticeImageMeta>> images = imagesOf(result.getContent());
        Map<Long, Org> orgs = orgsOf(result.getContent());
        Map<Long, String> authors = authorNamesOf(result.getContent());
        return PageResponse.of(result.getContent().stream()
                .map(notice -> AdminNoticeView.from(notice, orgs.get(notice.getOrgId()),
                        authors.get(notice.getCreatedBy()),
                        images.getOrDefault(notice.getId(), List.of()), now))
                .toList(), result);
    }

    private Page<Notice> pageFor(@Nullable AuthenticatedUser reader, Instant now,
            PageRequest pageRequest) {
        if (reader == null) {
            return noticeRepository.findVisibleToAnonymous(now, pageRequest);
        }
        Set<Long> orgIds = readerOrgIds(reader);
        return orgIds.isEmpty()
                ? noticeRepository.findPlatformVisible(now, pageRequest)
                : noticeRepository.findVisibleToUser(now, orgIds, pageRequest);
    }

    /** The notice behind a public id, or 404 when this reader may not see it. */
    private Notice readable(@Nullable AuthenticatedUser reader, UUID noticeId) {
        Instant now = Instant.now();
        // The same org set the list is built from, so a notice that cannot be
        // listed can never be fetched by id either.
        Set<Long> orgIds = reader == null ? Set.of() : readerOrgIds(reader);
        return noticeRepository.findByPublicId(noticeId)
                .filter(candidate -> visibleTo(candidate, reader, orgIds, now))
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
    }

    /**
     * The single statement of who may read a notice. Anonymous callers get
     * platform notices published PUBLIC; a signed-in caller gets every platform
     * notice plus the notices of {@code readerOrgIds}. Outside the active window
     * nobody gets any of them through this surface.
     */
    static boolean visibleTo(Notice notice, @Nullable AuthenticatedUser reader,
            Set<Long> readerOrgIds, Instant now) {
        if (!notice.isActiveAt(now)) {
            return false;
        }
        if (notice.getScope() == NoticeScope.PLATFORM) {
            return reader != null || notice.getAudience() == NoticeAudience.PUBLIC;
        }
        // An ORG notice is USERS-only by constraint, so a null reader never
        // reaches here with a match; contains keeps the comparison a value one.
        //
        // The argument is never null: notices_scope_target_check (V92) makes
        // org_id present exactly when scope is ORG, and PLATFORM returned above.
        // That is what keeps this clear of the hazard that an immutable set's
        // contains(null) throws rather than answering false — the anonymous
        // callers pass Set.of() in here. Should a notice ever be able to carry
        // a null org_id, this needs the null asked first, not the set changed.
        return reader != null && readerOrgIds.contains(notice.getOrgId());
    }

    /**
     * The organisations this reader belongs to, by the canonical <b>derived</b>
     * rule ({@link OrgMembershipSql}) rather than by their account's own column.
     *
     * <p>This is the whole reason the visibility query is built the way it is.
     * An ordinary account holds no granted organisation at all — {@code
     * user_org_roles} carries only the org tier — so reading the organisation
     * off the account would make an organisation's notices invisible to every
     * student it was written for. Membership is instead
     * derived from the organisations that own the resources of the workspaces
     * the reader is in, which is the same definition the announcement fan-out
     * uses. Two definitions of "이 기관 사람" is the hazard here: both surfaces
     * would keep returning well-formed answers while disagreeing about who is
     * in the organisation, and nothing would report it.</p>
     *
     * <p>The organisations the account is granted an <b>operating</b> role in
     * are unioned in on top, exactly as the announcement fan-out does, because
     * the canonical fragment covers the derived half only. Operating, not
     * readable: an ORG notice is addressed to that organisation's people, and a
     * viewer's <em>grant</em> does not make an account one of them — the grant
     * is what one organisation gives another's staff so they can look in. Such
     * an account reaches these notices through the management surface, which
     * exists for looking in, rather than through the board, which is where an
     * organisation's own people read what was addressed to them. What that does
     * not say is that a viewer is never one of them: the line above resolves
     * membership by workspace, and it asks nothing about roles, so a viewer who
     * works under the organisation is on the board like anyone else. Only the
     * grant is excluded here. The fan-out draws the same line for an ORG
     * announcement, and one product may not hold two answers to who
     * "이 기관 사람" are. The rule's {@code users.status = 'ACTIVE'} condition is
     * carried by the fragment itself, so a suspended account would derive no
     * organisation here even if it could reach this method — which it cannot,
     * since the JWT filter refuses to build a principal for one.</p>
     */
    private Set<Long> readerOrgIds(AuthenticatedUser reader) {
        Set<Long> orgIds = new HashSet<>(jdbcTemplate.queryForList(
                OrgMembershipSql.orgIdsOfMember("?"), Long.class, reader.id()));
        orgIds.addAll(reader.operatedOrgIds());
        return orgIds;
    }

    /**
     * Whether this caller may manage the notice — the same scope
     * {@link #listForAdmin} applies, restated here because this endpoint is
     * served without authentication and so carries no {@code @PreAuthorize} to
     * have narrowed the roles first. The sys tier manages everything; the org
     * tier manages its own organisation's notices and reads the platform's.
     *
     * <p>"The same scope" includes the refusal: an org-tier account granted no
     * organisation has no management view at all, so it gets no preview either.
     * Without that the two disagree on exactly one account — the platform
     * branch below asks no organisation question, so it would hand previews to
     * a caller whose {@link #listForAdmin} answers 403.
     */
    private static boolean manageableBy(@Nullable AuthenticatedUser reader, Notice notice) {
        if (reader == null) {
            return false;
        }
        if (reader.role().isSysTier()) {
            return true;
        }
        if (!reader.role().isOrgTier() || reader.readableOrgIds().isEmpty()) {
            return false;
        }
        return notice.getScope() == NoticeScope.PLATFORM
                || reader.reads(notice.getOrgId());
    }

    private Map<Long, List<NoticeImageMeta>> imagesOf(List<Notice> notices) {
        return noticeImageStore.metadataByNotice(notices.stream().map(Notice::getId).toList());
    }

    /**
     * Org rows behind the page's {@code org_id}s, batched into one read. The
     * result is a HashMap on purpose: callers look a platform notice's null
     * {@code orgId} up in it, and {@code Map.of()} throws on a null key.
     */
    private Map<Long, Org> orgsOf(List<Notice> notices) {
        List<Long> ids = notices.stream().map(Notice::getOrgId).filter(Objects::nonNull)
                .distinct().toList();
        return ids.isEmpty() ? new HashMap<>()
                : orgRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Org::getId, org -> org, (a, b) -> a,
                                HashMap::new));
    }

    /** Author display names for the admin list, batched the same way. */
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
