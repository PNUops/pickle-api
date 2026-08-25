package kr.ac.pusan.pickle.identity;

import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Linking and unlinking external identities on the caller's own account. */
@Service
public class IdentityService {

    private final UserIdentityRepository repository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public IdentityService(UserIdentityRepository repository, UserRepository userRepository,
            AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void unlink(AuthenticatedUser principal, IdentityProvider provider, String ip) {
        User user = userRepository.findById(principal.id()).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                        "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다."));
        UserIdentity identity = repository.findByProviderAndUserId(provider, user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "연동된 계정이 없습니다", "이 계정에는 해당 로그인 수단이 연동되어 있지 않습니다."));

        // The console disables the button, but the console is not the authority:
        // an account whose only way in is this link must not be able to remove
        // it, or it locks itself out with no recovery path (a password reset
        // mail sets a password, but only for someone who knows to ask for one).
        if (!user.hasPassword() && repository.findByUserIdOrderByLinkedAtAsc(user.getId()).size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDENTITY_LAST_METHOD,
                    "유일한 로그인 수단입니다",
                    "이 계정의 마지막 로그인 수단이라 해제할 수 없습니다. 먼저 비밀번호를 설정해 주세요.");
        }

        repository.delete(identity);
        auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_IDENTITY_UNLINKED,
                "user", user.getPublicId(),
                Map.of("provider", provider.name(), "identityEmail", identity.getEmailAtLink()), ip);
    }
}
