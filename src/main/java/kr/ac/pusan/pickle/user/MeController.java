package kr.ac.pusan.pickle.user;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.dto.UserProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code me}: GET /me — profile with group memberships. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MfaService mfaService;
    private final TermsService termsService;

    public MeController(UserRepository userRepository, GroupMemberRepository groupMemberRepository,
            MfaService mfaService, TermsService termsService) {
        this.userRepository = userRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.mfaService = mfaService;
        this.termsService = termsService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public UserProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                        "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요."));
        return UserProfileResponse.from(user, groupMemberRepository.findWithGroupByUserId(user.getId()),
                mfaService.isEnrolled(user.getId()), termsService.pendingConsents(user.getId()));
    }
}
