package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the internal business account chosen by a positive money grant. */
@Service
public class OpenRouterAccountSelectionService {

    private final OpenRouterAccountRepository repository;
    private final OpenRouterAccountCredentialRepository credentialRepository;
    private final OpenRouterManagementCredentialCipher credentialCipher;

    public OpenRouterAccountSelectionService(OpenRouterAccountRepository repository,
            OpenRouterAccountCredentialRepository credentialRepository,
            OpenRouterManagementCredentialCipher credentialCipher) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.credentialCipher = credentialCipher;
    }

    @Transactional
    public @Nullable OpenRouterAccount select(long orgId, @Nullable BigDecimal creditLimit,
            @Nullable UUID requestedAccountId) {
        boolean positive = creditLimit != null && creditLimit.signum() > 0;
        if (!positive) {
            if (requestedAccountId != null) {
                throw validation("openrouterAccountId",
                        "금액 한도가 0보다 클 때만 사업 계정을 선택할 수 있습니다.");
            }
            return null;
        }
        if (requestedAccountId != null) {
            OpenRouterAccount account = repository.findWithLockByPublicId(requestedAccountId)
                    .orElseThrow(OpenRouterAccountSelectionService::notFound);
            if (account.getOrgId() != orgId) {
                throw notFound();
            }
            if (!eligible(account)) {
                throw validation("openrouterAccountId",
                        "관리용 키까지 확인된 활성 사업 계정만 사용할 수 있습니다.");
            }
            return account;
        }
        List<FieldValidationError> errors = new ArrayList<>();
        OpenRouterAccount account = selectDefault(orgId, creditLimit, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return account;
    }

    /**
     * The account a positive money grant lands on when none is named: the
     * institution's one eligible account, its row locked. Answers through
     * {@code errors} rather than by throwing, because the bulk change asks
     * this inside one transaction, where an exception thrown through this
     * proxy would mark the whole batch for rollback.
     */
    @Transactional
    public @Nullable OpenRouterAccount selectDefault(long orgId, @Nullable BigDecimal creditLimit,
            List<FieldValidationError> errors) {
        if (creditLimit == null || creditLimit.signum() <= 0) {
            return null;
        }
        return theOneEligible(repository.findByOrgIdAndStatusOrderByNameAsc(orgId,
                OpenRouterAccountStatus.ACTIVE), errors);
    }

    /**
     * {@link #selectDefault} without the lock: what a judgment reads before
     * the write step locks the account it settled on. A preview must not lock
     * inside its read-only transaction, and a bulk apply must not lock an
     * account before it has taken the generation.
     */
    @Transactional(readOnly = true)
    public @Nullable OpenRouterAccount peekDefault(long orgId, @Nullable BigDecimal creditLimit,
            List<FieldValidationError> errors) {
        if (creditLimit == null || creditLimit.signum() <= 0) {
            return null;
        }
        return theOneEligible(repository.peekByOrgIdAndStatusOrderByNameAsc(orgId,
                OpenRouterAccountStatus.ACTIVE), errors);
    }

    private @Nullable OpenRouterAccount theOneEligible(List<OpenRouterAccount> candidates,
            List<FieldValidationError> errors) {
        List<OpenRouterAccount> eligible = candidates.stream().filter(this::eligible).toList();
        if (eligible.isEmpty()) {
            errors.add(new FieldValidationError("openrouterAccountId",
                    "유료 모델을 승인하려면 이 기관에 사업 계정이 필요합니다."));
            return null;
        }
        if (eligible.size() > 1) {
            errors.add(new FieldValidationError("openrouterAccountId",
                    "어느 사업 계정으로 결제할지 선택해 주세요."));
            return null;
        }
        return eligible.getFirst();
    }

    @Transactional
    public boolean eligible(OpenRouterAccount account) {
        if (account.getStatus() != OpenRouterAccountStatus.ACTIVE) {
            return false;
        }
        return databaseCredentialAvailable(account);
    }

    @Transactional
    public boolean databaseCredentialAvailable(OpenRouterAccount account) {
        OpenRouterAccountCredential active = credentialRepository
                .findByAccountIdAndStatus(account.getId(), OpenRouterCredentialStatus.ACTIVE)
                .orElse(null);
        if (active == null || active.getVerifiedAt() == null
                || invalidProof(active.getVerificationError())) {
            return false;
        }
        try {
            String plaintext = credentialCipher.decrypt(
                    account.getPublicId(), active.getCredentialEnc());
            return plaintext != null && !plaintext.isBlank();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static ApiException validation(String field, String message) {
        return ApiException.validationFailed(List.of(new FieldValidationError(field, message)));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 OpenRouter account를 찾을 수 없습니다.");
    }

    private static boolean invalidProof(@Nullable OpenRouterCredentialError error) {
        return error == OpenRouterCredentialError.CREDENTIAL_ERROR
                || error == OpenRouterCredentialError.VENDOR_REJECTED;
    }
}
