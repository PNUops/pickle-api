package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
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
    private final OpenRouterProperties properties;

    public OpenRouterAccountSelectionService(OpenRouterAccountRepository repository,
            OpenRouterAccountCredentialRepository credentialRepository,
            OpenRouterManagementCredentialCipher credentialCipher,
            OpenRouterProperties properties) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
    }

    @Transactional
    public @Nullable OpenRouterAccount select(long orgId, @Nullable BigDecimal creditLimit,
            @Nullable UUID requestedAccountId) {
        boolean positive = creditLimit != null && creditLimit.signum() > 0;
        if (!positive) {
            if (requestedAccountId != null) {
                throw validation("openrouterAccountId",
                        "금액 한도가 0보다 클 때만 OpenRouter 사업 account를 선택할 수 있습니다.");
            }
            return null;
        }
        if (!properties.accountBindingEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.OPENROUTER_ACCOUNT_BINDING_DISABLED,
                    "OpenRouter account binding을 사용할 수 없습니다",
                    "운영 전환이 완료될 때까지 새 금액 축 binding이 중지되어 있습니다.");
        }
        if (requestedAccountId != null) {
            OpenRouterAccount account = repository.findWithLockByPublicId(requestedAccountId)
                    .orElseThrow(OpenRouterAccountSelectionService::notFound);
            if (account.getOrgId() != orgId) {
                throw notFound();
            }
            if (!eligible(account)) {
                throw validation("openrouterAccountId",
                        "검증된 management credential이 있는 활성 account만 사용할 수 있습니다.");
            }
            return account;
        }
        List<OpenRouterAccount> eligible = repository.findByOrgIdAndStatusOrderByNameAsc(
                orgId, OpenRouterAccountStatus.ACTIVE).stream().filter(this::eligible).toList();
        if (eligible.isEmpty()) {
            throw validation("openrouterAccountId",
                    "금액 축을 승인하려면 이 기관에 OpenRouter 사업 account가 필요합니다.");
        }
        if (eligible.size() > 1) {
            throw validation("openrouterAccountId",
                    "사용할 OpenRouter 사업 account를 선택해 주세요.");
        }
        return eligible.getFirst();
    }

    @Transactional
    public boolean eligible(OpenRouterAccount account) {
        if (!properties.accountBindingEnabled()
                || account.getStatus() != OpenRouterAccountStatus.ACTIVE) {
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
