package kr.ac.pusan.pickle.user;

import kr.ac.pusan.pickle.common.error.ApiException;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.identity.UserIdentityRepository;
import kr.ac.pusan.pickle.profile.ProfileOptionsService;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.user.dto.UpdateProfileRequest;
import kr.ac.pusan.pickle.user.dto.UserProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code me}: GET /me — profile with workspace memberships,
 * linked identities and the 직책·소속 the console's profile gate reads — and
 * PUT /me/profile, which is how an account fills those in.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final MfaService mfaService;
    private final TermsService termsService;
    private final UserIdentityRepository userIdentityRepository;
    private final ProfileOptionsService profileOptionsService;
    private final ProfileValidator profileValidator;

    public MeController(UserRepository userRepository, OrgRepository orgRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            MfaService mfaService, TermsService termsService,
            UserIdentityRepository userIdentityRepository,
            ProfileOptionsService profileOptionsService, ProfileValidator profileValidator) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.mfaService = mfaService;
        this.termsService = termsService;
        this.userIdentityRepository = userIdentityRepository;
        this.profileOptionsService = profileOptionsService;
        this.profileValidator = profileValidator;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public UserProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return profileOf(loadUser(principal));
    }

    /**
     * Fills in (or corrects) 직책·학번·소속. Deliberately not gated behind
     * sudo-mode reauthentication: this is descriptive information about the
     * holder, it grants nothing, and the accounts that most need it are the
     * ones created through an external identity, which have no password to
     * re-type.
     */
    @PutMapping("/profile")
    @Transactional
    public UserProfileResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        User user = loadUser(principal);
        profileValidator.validate(request.position(), request.studentNo(), request.departmentCode());
        user.setProfile(request.position(),
                ProfileValidator.normalizeStudentNo(request.position(), request.studentNo()),
                request.departmentCode());
        return profileOf(userRepository.save(user));
    }

    private User loadUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                        "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요."));
    }

    private UserProfileResponse profileOf(User user) {
        UUID orgId = user.getOrgId() == null ? null
                : orgRepository.findById(user.getOrgId()).map(Org::getPublicId).orElse(null);
        return UserProfileResponse.from(user, orgId,
                workspaceMemberRepository.findWithWorkspaceByUserId(user.getId()),
                mfaService.isEnrolled(user.getId()), termsService.pendingConsents(user.getId()),
                profileOptionsService.departmentName(user.getDepartmentCode()),
                userIdentityRepository.findByUserIdOrderByLinkedAtAsc(user.getId()));
    }
}
