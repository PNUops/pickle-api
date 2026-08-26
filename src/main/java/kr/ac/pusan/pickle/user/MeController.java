package kr.ac.pusan.pickle.user;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.orgs.ManagedOrgQueryService;
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
 * linked identities and the 직책·소속 학과 the console's profile prompt reads —
 * and PUT /me/profile, which is how an account fills those in and renames
 * itself.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;
    private final ManagedOrgQueryService managedOrgQueryService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final MfaService mfaService;
    private final TermsService termsService;
    private final UserIdentityRepository userIdentityRepository;
    private final ProfileOptionsService profileOptionsService;
    private final ProfileValidator profileValidator;
    private final AuditService auditService;

    public MeController(UserRepository userRepository,
            ManagedOrgQueryService managedOrgQueryService,
            WorkspaceMemberRepository workspaceMemberRepository,
            MfaService mfaService, TermsService termsService,
            UserIdentityRepository userIdentityRepository,
            ProfileOptionsService profileOptionsService, ProfileValidator profileValidator,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.managedOrgQueryService = managedOrgQueryService;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.mfaService = mfaService;
        this.termsService = termsService;
        this.userIdentityRepository = userIdentityRepository;
        this.profileOptionsService = profileOptionsService;
        this.profileValidator = profileValidator;
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public UserProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return profileOf(loadUser(principal));
    }

    /**
     * Fills in, corrects, or clears 이름·직책·학번·소속 학과. Deliberately not
     * gated behind sudo-mode reauthentication: this is descriptive information
     * about the holder, it grants nothing, and the accounts that most need it
     * are the ones created through an external identity, which have no
     * password to re-type.
     *
     * <p>Every field is optional and presence-tracked, so a request that
     * carries only 이름 leaves the profile untouched. Validation runs against
     * the RESULT of the merge rather than against the request: whether 학번 is
     * required depends on the position, so a request that changes only the
     * position has to be judged against the 학번 already on the row.
     */
    @PutMapping("/profile")
    @Transactional
    public UserProfileResponse updateMyProfile(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest request, HttpServletRequest httpRequest) {
        User user = loadUser(principal);
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("position", "수정할 값을 하나 이상 보내 주세요.")));
        }
        if (request.isNameSet() && (request.getName() == null || request.getName().isBlank())) {
            // users.name is NOT NULL, and an account with no name has nowhere
            // to be displayed. Clearing is refused rather than ignored.
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("name", "이름을 입력해 주세요.")));
        }

        UserPosition position = request.isPositionSet() ? request.getPosition() : user.getPosition();
        String studentNo = request.isStudentNoSet() ? request.getStudentNo() : user.getStudentNo();
        String departmentCode = request.isDepartmentCodeSet()
                ? request.getDepartmentCode() : user.getDepartmentCode();
        profileValidator.validate(position, studentNo, departmentCode);

        String previousName = user.getName();
        String previousStudentNo = user.getStudentNo();
        UserPosition previousPosition = user.getPosition();
        if (request.isNameSet()) {
            user.setName(request.getName().strip());
        }
        user.setProfile(position, ProfileValidator.normalizeStudentNo(position, studentNo),
                departmentCode);
        // Audited like every other self-service write on /me. 학번 is a personal
        // identifier the holder can rewrite at will, so "who changed this and
        // when" has to have an answer; the before-values are recorded because
        // the after-values are already readable on the row. 학번 itself is
        // recorded as a boolean rather than a value — the audit log is not a
        // second place to keep it.
        //
        // String.valueOf wraps every value, not only the enum: Map.of throws on
        // a null VALUE, and 소속 학과 has been nullable since v0.46.0.
        auditService.recordAfterCommit(user.getId(), user.getRole().name(),
                AuditService.ACCOUNT_PROFILE_UPDATE, "user", user.getPublicId(),
                Map.of("position", String.valueOf(position),
                        "departmentCode", String.valueOf(departmentCode),
                        "previousPosition", String.valueOf(previousPosition),
                        "previousStudentNoSet", String.valueOf(previousStudentNo != null),
                        "previousName", previousName),
                clientIp(httpRequest));
        return profileOf(userRepository.save(user));
    }

    private User loadUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                        "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요."));
    }

    private UserProfileResponse profileOf(User user) {
        return UserProfileResponse.from(user, managedOrgQueryService.of(user.getId()),
                workspaceMemberRepository.findWithWorkspaceByUserId(user.getId()),
                mfaService.isEnrolled(user.getId()), termsService.pendingConsents(user.getId()),
                profileOptionsService.departmentName(user.getDepartmentCode()),
                userIdentityRepository.findByUserIdOrderByLinkedAtAsc(user.getId()));
    }
}
