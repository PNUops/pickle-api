package kr.ac.pusan.pickle.notice;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.notice.dto.AdminNoticeView;
import kr.ac.pusan.pickle.notice.dto.NoticeCreateRequest;
import kr.ac.pusan.pickle.notice.dto.NoticeImageView;
import kr.ac.pusan.pickle.notice.dto.NoticeUpdateRequest;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The notice writes (contract {@code createAdminNotice} and friends).
 *
 * <p>Method security has already narrowed these to ORG_ADMIN and SYS_ADMIN.
 * What it cannot express, and what lives here, is the rest of the rule:</p>
 *
 * <ul>
 *   <li>an ORG_ADMIN writes only ORG notices, only for their own organisation —
 *       a PLATFORM request or another organisation's id is 403, the same shape
 *       an ALL-scope announcement gets. Naming their <em>own</em> organisation
 *       is accepted, not refused as a field they may not set: the console sends
 *       it for every role;</li>
 *   <li>an ORG_ADMIN edits, deletes and attaches to only their own
 *       organisation's notices. Another organisation's answers 404 because they
 *       cannot see it either; a platform notice answers 403 because they can —
 *       it is in their list, read-only, and masking it would say the opposite
 *       of what the list already showed;</li>
 *   <li>{@code audience=PUBLIC} on an ORG notice is a 422 validation error,
 *       re-checked on update against the <em>stored</em> scope. The database
 *       refuses it too, but a constraint violation would surface as a 500 and
 *       tell the author nothing;</li>
 *   <li>{@code scope} and {@code orgId} are absent from the update body
 *       entirely. Were they editable, an ORG_ADMIN could create an ORG notice
 *       and then promote it to PLATFORM or move it to another organisation,
 *       which is the create gate defeated through a second verb.</li>
 * </ul>
 *
 * <p>Images are validated by what their bytes are rather than by what the
 * upload claimed ({@link NoticeImageTypes}), capped per image and per notice,
 * and stored through {@link NoticeImageStore}. Deleting a notice takes its
 * images with it through the foreign key's cascade.</p>
 */
@Service
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final NoticeImageStore noticeImageStore;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public NoticeService(NoticeRepository noticeRepository, NoticeImageStore noticeImageStore,
            OrgRepository orgRepository, UserRepository userRepository,
            AuditService auditService) {
        this.noticeRepository = noticeRepository;
        this.noticeImageStore = noticeImageStore;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AdminNoticeView create(AuthenticatedUser actor, NoticeCreateRequest request, String ip) {
        Long orgId = resolveCreateTarget(actor, request);
        List<FieldValidationError> errors = new ArrayList<>();
        if (request.scope() == NoticeScope.ORG && request.audience() == NoticeAudience.PUBLIC) {
            errors.add(new FieldValidationError("audience",
                    "기관 공지는 로그인한 사용자에게만 공개할 수 있습니다."));
        }
        Instant startsAt = request.startsAt() == null ? Instant.now() : request.startsAt();
        if (request.endsAt() != null && !request.endsAt().isAfter(startsAt)) {
            errors.add(new FieldValidationError("endsAt", "게시 종료 시각은 시작 시각보다 뒤여야 합니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        Notice notice = noticeRepository.saveAndFlush(new Notice(actor.id(), request.scope(),
                orgId, request.audience(), request.title().strip(), request.body().strip(),
                request.isPinned(), request.isPopup(), startsAt, request.endsAt()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.NOTICE_CREATE,
                "notice", notice.getPublicId(),
                Map.of("scope", notice.getScope().name(), "audience", notice.getAudience().name(),
                        "title", notice.getTitle(), "pinned", notice.isPinned(),
                        "popup", notice.isPopup()), ip);
        return view(notice);
    }

    @Transactional
    public AdminNoticeView update(AuthenticatedUser actor, UUID noticeId,
            NoticeUpdateRequest request, String ip) {
        Notice notice = writable(actor, noticeId);
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("body", "변경할 항목을 하나 이상 보내 주세요.")));
        }
        // Field-by-field so the audit records what actually changed, and so the
        // cross-field checks below see the notice as it will be, not as it was.
        Map<String, Object> changed = new LinkedHashMap<>();
        NoticeAudience audience = request.isAudienceSet() && request.getAudience() != null
                ? request.getAudience() : notice.getAudience();
        Instant startsAt = request.isStartsAtSet() && request.getStartsAt() != null
                ? request.getStartsAt() : notice.getStartsAt();
        Instant endsAt = request.isEndsAtSet() ? request.getEndsAt() : notice.getEndsAt();

        List<FieldValidationError> errors = new ArrayList<>();
        if (notice.getScope() == NoticeScope.ORG && audience == NoticeAudience.PUBLIC) {
            errors.add(new FieldValidationError("audience",
                    "기관 공지는 로그인한 사용자에게만 공개할 수 있습니다."));
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            errors.add(new FieldValidationError("endsAt", "게시 종료 시각은 시작 시각보다 뒤여야 합니다."));
        }
        if (request.isTitleSet() && Texts.blankToNull(request.getTitle()) == null) {
            errors.add(new FieldValidationError("title", "제목을 입력해 주세요."));
        }
        if (request.isBodySet() && Texts.blankToNull(request.getBody()) == null) {
            errors.add(new FieldValidationError("body", "본문을 입력해 주세요."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        if (request.isTitleSet() && !notice.getTitle().equals(request.getTitle().strip())) {
            notice.setTitle(request.getTitle().strip());
            changed.put("title", notice.getTitle());
        }
        if (request.isBodySet() && !notice.getBody().equals(request.getBody().strip())) {
            notice.setBody(request.getBody().strip());
            changed.put("bodyChanged", true);
        }
        if (audience != notice.getAudience()) {
            notice.setAudience(audience);
            changed.put("audience", audience.name());
        }
        if (request.isPinnedSet() && request.getPinned() != null
                && request.getPinned() != notice.isPinned()) {
            notice.setPinned(request.getPinned());
            changed.put("pinned", notice.isPinned());
        }
        if (request.isPopupSet() && request.getPopup() != null
                && request.getPopup() != notice.isPopup()) {
            notice.setPopup(request.getPopup());
            changed.put("popup", notice.isPopup());
        }
        if (!startsAt.equals(notice.getStartsAt())) {
            notice.setStartsAt(startsAt);
            changed.put("startsAt", startsAt.toString());
        }
        if (request.isEndsAtSet() && !Objects.equals(endsAt, notice.getEndsAt())) {
            notice.setEndsAt(endsAt);
            // Null is written as a JSON null: "this notice stopped expiring" is
            // the change, and a placeholder string would read as a value.
            changed.put("endsAt", endsAt == null ? null : endsAt.toString());
        }

        if (!changed.isEmpty()) {
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.NOTICE_UPDATE, "notice", notice.getPublicId(), changed, ip);
        }
        return view(notice);
    }

    @Transactional
    public void delete(AuthenticatedUser actor, UUID noticeId, String ip) {
        Notice notice = writable(actor, noticeId);
        UUID publicId = notice.getPublicId();
        String title = notice.getTitle();
        String scope = notice.getScope().name();
        // The images go with it: notice_images.notice_id cascades on delete.
        noticeRepository.delete(notice);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.NOTICE_DELETE,
                "notice", publicId, Map.of("title", title, "scope", scope), ip);
    }

    @Transactional
    public NoticeImageView addImage(AuthenticatedUser actor, UUID noticeId, MultipartFile file,
            String ip) {
        Notice notice = writable(actor, noticeId);
        if (file == null || file.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("file", "이미지 파일을 첨부해 주세요.")));
        }
        if (noticeImageStore.count(notice.getId()) >= NoticeImageTypes.MAX_PER_NOTICE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOTICE_IMAGE_LIMIT_EXCEEDED,
                    "이미지를 더 첨부할 수 없습니다",
                    "공지 하나에 첨부할 수 있는 이미지는 " + NoticeImageTypes.MAX_PER_NOTICE + "장까지입니다.");
        }
        byte[] bytes = read(file);
        if (bytes.length > NoticeImageTypes.MAX_BYTES) {
            throw imageTooLarge();
        }
        // The declared Content-Type is never consulted: the type is whatever the
        // leading bytes say it is, and anything off the whitelist is refused.
        String contentType = NoticeImageTypes.sniff(bytes)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCodes.NOTICE_IMAGE_TYPE_UNSUPPORTED, "지원하지 않는 이미지 형식입니다",
                        "PNG, JPEG, WebP, GIF 이미지만 첨부할 수 있습니다."));
        NoticeImageMeta stored = noticeImageStore.store(notice.getId(),
                Texts.sanitizeReported(file.getOriginalFilename(), 255), contentType, bytes);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.NOTICE_IMAGE_ADD, "notice", notice.getPublicId(),
                Map.of("imageId", stored.id().toString(), "contentType", stored.contentType(),
                        "byteSize", stored.byteSize()), ip);
        return NoticeImageView.from(notice.getPublicId(), stored);
    }

    @Transactional
    public void deleteImage(AuthenticatedUser actor, UUID noticeId, UUID imageId, String ip) {
        Notice notice = writable(actor, noticeId);
        if (!noticeImageStore.delete(notice.getId(), imageId)) {
            throw notFound("해당 이미지가 존재하지 않습니다.");
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.NOTICE_IMAGE_DELETE, "notice", notice.getPublicId(),
                Map.of("imageId", imageId.toString()), ip);
    }

    /**
     * Which organisation the new notice belongs to, and the refusal when the
     * caller may not write it at all.
     *
     * <p>Shaped after {@code AnnouncementService}, which answers the same
     * question for the same kind of object — an org-scoped write naming its
     * target organisation in the body — so the two cannot drift: naming an
     * organisation the caller does not administer is a <b>field</b> error (422)
     * rather than a refusal, because {@code orgId} is a submitted value and
     * there is no existing row whose existence a 404 would be protecting. That
     * is the opposite of {@link #writable}, where the notice is addressed by id
     * and masking is what keeps another organisation's notices private.
     *
     * <p>Co-appointment: an account can administer several organisations, so
     * "your own org" is not always a single answer. Naming one is required from
     * the second organisation onwards; an account administering exactly one
     * still need not.
     */
    private Long resolveCreateTarget(AuthenticatedUser actor, NoticeCreateRequest request) {
        // A platform notice names no organisation, and saying so beats ignoring
        // it: a client that sent one believes something the server does not.
        if (request.scope() == NoticeScope.PLATFORM) {
            if (actor.role() == UserRole.ORG_ADMIN) {
                throw forbidden("전역 공지는 시스템 관리자만 등록할 수 있습니다.");
            }
            if (request.orgId() != null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                        "전역 공지에는 기관을 지정할 수 없습니다.")));
            }
            return null;
        }
        Long requestedOrgId = request.orgId() == null ? null
                : orgRepository.findByPublicId(request.orgId()).map(Org::getId).orElse(null);
        if (actor.role() == UserRole.ORG_ADMIN) {
            // administeredOrgIds(), not the effective role: since V90 the account
            // may hold ORG_ADMIN in one organisation and only read another, and
            // the effective role the @PreAuthorize gate saw is the highest of
            // them. Asking the role here would let an admin of one organisation
            // write a notice for every organisation it can merely see.
            Set<Long> administered = actor.administeredOrgIds();
            if (administered.isEmpty()) {
                throw forbidden("관리 기관이 지정되지 않은 계정입니다.");
            }
            if (request.orgId() != null) {
                // Another organisation's is refused rather than quietly
                // rewritten to one of theirs: an attempted cross-org write is
                // exactly the event worth surfacing. An organisation that does
                // not exist answers the same way, so which organisations exist
                // stays private from the org tier.
                // requestedOrgId is null when the id names no organisation, and
                // administeredOrgIds() is a JDK immutable set, whose contains()
                // throws on null rather than answering false. Asking the null
                // question first is what keeps the unknown org a 422 like any
                // other unreachable one instead of a 500.
                if (requestedOrgId == null || !administered.contains(requestedOrgId)) {
                    throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                            "자기 기관의 공지만 등록할 수 있습니다.")));
                }
                return requestedOrgId;
            }
            if (administered.size() == 1) {
                return administered.iterator().next();
            }
            throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                    "기관 공지에는 대상 기관이 필요합니다.")));
        }
        // The sys tier administers no organisation, so it always names one.
        if (request.orgId() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                    "기관 공지에는 대상 기관이 필요합니다.")));
        }
        if (requestedOrgId == null) {
            throw notFound("해당 기관이 존재하지 않습니다.");
        }
        return requestedOrgId;
    }

    /**
     * The notice a write may touch. An organisation administrator reaches only
     * the notices of the organisations they <b>administer</b>; a platform notice
     * is visible to them but not theirs to change, and any other organisation's
     * is neither — including one they hold a reading role in, which is why this
     * asks {@link AuthenticatedUser#administers} rather than reusing the wider
     * scope the management list is built from.
     */
    private Notice writable(AuthenticatedUser actor, UUID noticeId) {
        Notice notice = noticeRepository.findByPublicId(noticeId)
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
        if (actor.role().isSysTier()) {
            return notice;
        }
        if (notice.getScope() == NoticeScope.PLATFORM) {
            throw forbidden("전역 공지는 시스템 관리자만 수정할 수 있습니다.");
        }
        if (!actor.administers(notice.getOrgId())) {
            throw notFound("해당 공지가 존재하지 않습니다.");
        }
        return notice;
    }

    private AdminNoticeView view(Notice notice) {
        Org org = notice.getOrgId() == null ? null
                : orgRepository.findById(notice.getOrgId()).orElse(null);
        String author = notice.getCreatedBy() == null ? null
                : userRepository.findById(notice.getCreatedBy()).map(User::getName).orElse(null);
        return AdminNoticeView.from(notice, org, author,
                noticeImageStore.metadataByNotice(List.of(notice.getId()))
                        .getOrDefault(notice.getId(), List.of()),
                Instant.now());
    }

    /**
     * The uploaded bytes. A body that overruns the container's own multipart
     * cap never reaches here at all — it aborts during parsing and
     * {@code GlobalExceptionHandler} answers the same 413 this class does. What
     * is left is a genuinely unreadable upload, which is the client's to retry.
     */
    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException broken) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("file", "이미지를 읽지 못했습니다. 다시 첨부해 주세요.")));
        }
    }

    private static ApiException imageTooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.NOTICE_IMAGE_TOO_LARGE,
                "이미지가 너무 큽니다", "이미지 한 장은 2 MiB까지 첨부할 수 있습니다.");
    }

    private static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", detail);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
