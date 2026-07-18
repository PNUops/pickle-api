package kr.ac.pusan.pickle.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;
import kr.ac.pusan.pickle.consent.dto.ConsentView;
import kr.ac.pusan.pickle.consent.dto.TermsDocumentView;
import kr.ac.pusan.pickle.consent.dto.TermsVersionView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Terms/privacy documents and per-user consent (M6 W2-A). The "current" version
 * of a document is the highest already-effective {@link TermsVersion}; a user is
 * "pending consent" for a document until they hold a row for its current
 * version. Signup must cover every current document; re-consent adds rows for
 * revised versions.
 */
@Service
public class TermsService {

    private final TermsVersionRepository termsVersionRepository;
    private final UserConsentRepository userConsentRepository;

    public TermsService(TermsVersionRepository termsVersionRepository,
            UserConsentRepository userConsentRepository) {
        this.termsVersionRepository = termsVersionRepository;
        this.userConsentRepository = userConsentRepository;
    }

    // ── public reads (/meta/terms) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TermsVersionView> currentVersions() {
        return currentVersionEntities().stream().map(TermsVersionView::from).toList();
    }

    @Transactional(readOnly = true)
    public TermsDocumentView currentDocument(TermsDocType docType) {
        return currentVersion(docType).map(TermsDocumentView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "문서를 찾을 수 없습니다", "요청한 문서가 존재하지 않습니다."));
    }

    // ── per-user consent (/me/consents, /me profile) ─────────────────────────

    @Transactional(readOnly = true)
    public List<ConsentView> listConsents(long userId) {
        return userConsentRepository.findConsentHistory(userId);
    }

    /** Current documents the user has not yet consented to (drives the console consent gate). */
    @Transactional(readOnly = true)
    public List<TermsVersionView> pendingConsents(long userId) {
        List<TermsVersionView> pending = new ArrayList<>();
        for (TermsVersion current : currentVersionEntities()) {
            if (!userConsentRepository.existsByUserIdAndTermsVersionId(userId, current.getId())) {
                pending.add(TermsVersionView.from(current));
            }
        }
        return pending;
    }

    /** Re-consent to revised documents; a submitted non-current version is 409. */
    @Transactional
    public List<ConsentView> acceptConsents(long userId, List<ConsentInput> inputs) {
        for (ConsentInput input : inputs) {
            TermsVersion current = currentVersion(input.docType())
                    .orElseThrow(TermsService::versionMismatch);
            if (current.getVersion() != input.version()) {
                throw versionMismatch();
            }
            recordConsent(userId, current.getId());
        }
        return listConsents(userId);
    }

    // ── signup + seeding ─────────────────────────────────────────────────────

    /**
     * Validates a signup's consents cover <b>every</b> current document at its
     * current version (422 with field errors otherwise) and records the rows in
     * the caller's transaction.
     */
    @Transactional
    public void recordSignupConsents(long userId, List<ConsentInput> inputs) {
        List<TermsVersion> current = currentVersionEntities();
        List<FieldValidationError> errors = new ArrayList<>();
        List<ConsentInput> submitted = inputs == null ? List.of() : inputs;
        for (TermsVersion doc : current) {
            boolean ok = submitted.stream().anyMatch(
                    c -> c.docType() == doc.getDocType() && c.version() == doc.getVersion());
            if (!ok) {
                errors.add(new FieldValidationError("consents",
                        doc.getTitle() + "(v" + doc.getVersion() + ") 동의가 필요합니다."));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        for (TermsVersion doc : current) {
            recordConsent(userId, doc.getId());
        }
    }

    /** Idempotently grants consent to every current document (dev seed accounts). */
    @Transactional
    public void ensureCurrentConsents(long userId) {
        for (TermsVersion doc : currentVersionEntities()) {
            recordConsent(userId, doc.getId());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void recordConsent(long userId, long termsVersionId) {
        if (!userConsentRepository.existsByUserIdAndTermsVersionId(userId, termsVersionId)) {
            userConsentRepository.save(new UserConsent(userId, termsVersionId));
        }
    }

    private Optional<TermsVersion> currentVersion(TermsDocType docType) {
        return termsVersionRepository
                .findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByVersionDesc(docType, Instant.now());
    }

    private List<TermsVersion> currentVersionEntities() {
        List<TermsVersion> current = new ArrayList<>();
        for (TermsDocType docType : TermsDocType.values()) {
            currentVersion(docType).ifPresent(current::add);
        }
        return current;
    }

    private static ApiException versionMismatch() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONSENT_VERSION_MISMATCH,
                "약관 버전이 갱신되었습니다",
                "약관이 개정되었습니다. 최신 내용을 확인한 뒤 다시 동의해 주세요.");
    }
}
