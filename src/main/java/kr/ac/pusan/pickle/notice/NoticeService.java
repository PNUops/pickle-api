package kr.ac.pusan.pickle.notice;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The notice writes (contract {@code createAdminNotice} and friends).
 *
 * <p>Method security has already narrowed these to ORG_ADMIN and SYS_ADMIN, and
 * since V95 that is very nearly the whole rule. A notice belongs to no
 * organisation, so the two roles write the same thing: an organisation
 * administrator publishes to every user of the platform, and to anonymous
 * visitors too when it sets {@code popup}. An organisation names who supplies a
 * node or a resource; it is not a mechanism for deciding who may use a feature,
 * which is what the old scope axis had made it.</p>
 *
 * <p>What is left here on top of the gate:</p>
 *
 * <ul>
 *   <li>a notice named by a path that resolves to nothing is 404. That is a
 *       fact rather than a mask: everyone through the gate may write every
 *       notice, so there is nothing left for a masked answer to protect;</li>
 *   <li>the publication window is validated rather than left to
 *       {@code notices_window_check}, which would surface as a 500 and tell the
 *       author nothing;</li>
 *   <li>{@code popup} is the only visibility control, and it is editable.
 *       Turning it on after the fact is an ordinary edit, and the consequence —
 *       text and images in front of anonymous visitors — is the same one the
 *       create path carries. It is also a coupling worth knowing about: there
 *       is no way to raise a modal for signed-in readers alone, because the
 *       flag that interrupts them is the flag that opens the notice up.</li>
 * </ul>
 *
 * <p>The masking that does remain is on the public read path, not here: an
 * anonymous caller asking for a notice that is not a popup gets 404 rather than
 * 403, because a refusal would confirm that the identifier names a real notice
 * ({@link NoticeQueryService}).</p>
 *
 * <p>Images are validated by what their bytes are rather than by what the
 * upload claimed ({@link NoticeImageTypes}), capped at 2 MiB each and 5 per
 * notice ({@code NoticeImageTypes.MAX_BYTES} and {@code MAX_PER_NOTICE}),
 * and stored through {@link NoticeImageStore}. Deleting a notice takes its
 * images with it through the foreign key's cascade.</p>
 */
@Service
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final NoticeImageStore noticeImageStore;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public NoticeService(NoticeRepository noticeRepository, NoticeImageStore noticeImageStore,
            UserRepository userRepository, AuditService auditService) {
        this.noticeRepository = noticeRepository;
        this.noticeImageStore = noticeImageStore;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AdminNoticeView create(AuthenticatedUser actor, NoticeCreateRequest request, String ip) {
        List<FieldValidationError> errors = new ArrayList<>();
        Instant startsAt = request.startsAt() == null ? Instant.now() : request.startsAt();
        if (request.endsAt() != null && !request.endsAt().isAfter(startsAt)) {
            errors.add(new FieldValidationError("endsAt", "게시 종료 시각은 시작 시각보다 뒤여야 합니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        Notice notice = noticeRepository.saveAndFlush(new Notice(actor.id(),
                request.title().strip(), request.body().strip(),
                request.isPinned(), request.isPopup(), startsAt, request.endsAt()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.NOTICE_CREATE,
                "notice", notice.getPublicId(),
                Map.of("title", notice.getTitle(), "pinned", notice.isPinned(),
                        "popup", notice.isPopup()), ip);
        return view(notice);
    }

    @Transactional
    public AdminNoticeView update(AuthenticatedUser actor, UUID noticeId,
            NoticeUpdateRequest request, String ip) {
        Notice notice = writable(noticeId);
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("body", "변경할 항목을 하나 이상 보내 주세요.")));
        }
        // Field-by-field so the audit records what actually changed, and so the
        // cross-field checks below see the notice as it will be, not as it was.
        Map<String, Object> changed = new LinkedHashMap<>();
        Instant startsAt = request.isStartsAtSet() && request.getStartsAt() != null
                ? request.getStartsAt() : notice.getStartsAt();
        Instant endsAt = request.isEndsAtSet() ? request.getEndsAt() : notice.getEndsAt();

        List<FieldValidationError> errors = new ArrayList<>();
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
        Notice notice = writable(noticeId);
        UUID publicId = notice.getPublicId();
        String title = notice.getTitle();
        // The images go with it: notice_images.notice_id cascades on delete.
        noticeRepository.delete(notice);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.NOTICE_DELETE,
                "notice", publicId, Map.of("title", title), ip);
    }

    @Transactional
    public NoticeImageView addImage(AuthenticatedUser actor, UUID noticeId, MultipartFile file,
            String ip) {
        Notice notice = writable(noticeId);
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
        Notice notice = writable(noticeId);
        if (!noticeImageStore.delete(notice.getId(), imageId)) {
            throw notFound("해당 이미지가 존재하지 않습니다.");
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.NOTICE_IMAGE_DELETE, "notice", notice.getPublicId(),
                Map.of("imageId", imageId.toString()), ip);
    }

    /**
     * The notice a write may touch — since V95, any of them. Everyone the
     * controller's gate admits administers every notice, so this resolves the
     * identifier and nothing more.
     *
     * <p>The 404 is therefore a fact and not a mask. It used to be one: an
     * organisation administrator got the same answer for another
     * organisation's notice as for one that did not exist, because
     * distinguishing them would have disclosed that the identifier named
     * something. There is no such scope left to hide, and the masking that
     * remains is on the public read path in {@link NoticeQueryService}.
     */
    private Notice writable(UUID noticeId) {
        return noticeRepository.findByPublicId(noticeId)
                .orElseThrow(() -> notFound("해당 공지가 존재하지 않습니다."));
    }

    private AdminNoticeView view(Notice notice) {
        String author = notice.getCreatedBy() == null ? null
                : userRepository.findById(notice.getCreatedBy()).map(User::getName).orElse(null);
        return AdminNoticeView.from(notice, author,
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

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
