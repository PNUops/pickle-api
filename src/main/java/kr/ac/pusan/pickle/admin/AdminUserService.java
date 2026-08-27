package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminUpdateProfileRequest;
import kr.ac.pusan.pickle.admin.dto.UserAdminDetailResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RefreshTokenService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.profile.ProfileLock;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserStatusChange;
import kr.ac.pusan.pickle.user.UserStatusChangeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SYS_ADMIN account disable/enable ({@code POST /admin/users/{userId}/
 * disable|enable}). Disable is reversible: {@code enable} restores the
 * {@code fromStatus} of the matching disable row so an unverified account
 * comes back as PENDING_VERIFICATION, never a verification bypass.
 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AdminUserQueryService adminUserQueryService;
    private final MfaService mfaService;
    private final RefreshTokenService refreshTokenService;
    private final ProfileValidator profileValidator;

    public AdminUserService(UserRepository userRepository,
            UserStatusChangeRepository userStatusChangeRepository, AuditService auditService,
            NotificationService notificationService, AdminUserQueryService adminUserQueryService,
            MfaService mfaService, RefreshTokenService refreshTokenService,
            ProfileValidator profileValidator) {
        this.userRepository = userRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.adminUserQueryService = adminUserQueryService;
        this.mfaService = mfaService;
        this.refreshTokenService = refreshTokenService;
        this.profileValidator = profileValidator;
    }

    /**
     * SYS_ADMIN 2FA reset ({@code POST /admin/users/{userId}/mfa-reset}): clears
     * the target's enrollment + recovery codes after offline identity checks.
     * 404 if the user is unknown, 409 if they are not enrolled.
     */
    @Transactional
    public MessageResponse resetMfa(AuthenticatedUser actor, UUID userId, String ip) {
        User target = userRepository.findByPublicId(userId).orElseThrow(AdminUserService::userNotFound);
        mfaService.adminReset(actor.id(), actor.role().name(), target, ip);
        return new MessageResponse(
                "2단계 인증을 초기화했습니다. 사용자는 비밀번호로 로그인한 뒤 다시 등록해야 합니다.");
    }

    /**
     * SYS_ADMIN profile correction ({@code PATCH /admin/users/{userId}/profile}).
     *
     * <p>The counterpart of the write-once lock on {@code PUT /me/profile}: the
     * holder fills 직책·학번·소속 once and an administrator is who moves them
     * after that. Kept to SYS_ADMIN like every other write on another account —
     * 학번 identifies a real person and a wrong one is not the holder's to fix,
     * so widening this later is a decision, not an oversight.
     *
     * <p>Unlike the holder's path this may clear a value, because the case it
     * exists for is a value that should never have been there.
     */
    @Transactional
    public UserAdminDetailResponse updateProfile(AuthenticatedUser actor, UUID userId,
            AdminUpdateProfileRequest request, String ip) {
        User user = userRepository.findByPublicId(userId).orElseThrow(AdminUserService::userNotFound);
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("position", "수정할 값을 하나 이상 보내 주세요.")));
        }

        UserPosition position = request.isPositionSet() ? request.getPosition() : user.getPosition();
        String studentNo = request.isStudentNoSet() ? request.getStudentNo() : user.getStudentNo();
        String departmentCode = request.isDepartmentCodeSet()
                ? request.getDepartmentCode() : user.getDepartmentCode();
        String departmentOther = request.isDepartmentOtherSet()
                ? request.getDepartmentOther() : user.getDepartmentOther();
        // The same value rules the holder's path runs. An administrator may
        // correct a profile, not store one the CHECK constraints refuse.
        boolean codeIsNew = request.isDepartmentCodeSet()
                && !java.util.Objects.equals(user.getDepartmentCode(), departmentCode);
        profileValidator.validate(position, studentNo, departmentCode, departmentOther, codeIsNew);

        UserPosition previousPosition = user.getPosition();
        String previousStudentNo = user.getStudentNo();
        String previousDepartmentCode = user.getDepartmentCode();
        boolean droppedStudentNo =
                ProfileLock.positionChangeDropsStudentNo(previousPosition, position, previousStudentNo);
        user.setProfile(position, ProfileValidator.normalizeStudentNo(position, studentNo),
                departmentCode, ProfileValidator.normalizeDepartmentOther(departmentOther));
        userRepository.save(user);

        // 학번 is recorded as set/not-set rather than as a value, matching the
        // holder's own entry: the audit log is not a second place to keep it.
        // droppedStudentNo is recorded because a 직책 change discards the 학번
        // without the request ever mentioning it, and an entry that does not
        // say so reads as if the value were still there.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("position", String.valueOf(position));
        payload.put("departmentCode", String.valueOf(departmentCode));
        payload.put("departmentOtherSet", String.valueOf(departmentOther != null));
        payload.put("studentNoSet", String.valueOf(user.getStudentNo() != null));
        payload.put("previousPosition", String.valueOf(previousPosition));
        payload.put("previousDepartmentCode", String.valueOf(previousDepartmentCode));
        payload.put("previousStudentNoSet", String.valueOf(previousStudentNo != null));
        payload.put("studentNoDroppedByPositionChange", String.valueOf(droppedStudentNo));
        payload.put("reason", String.valueOf(request.getReason()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.USER_PROFILE_UPDATE, "user", user.getPublicId(), payload, ip);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("userId", user.getId());
        args.put("userEmail", user.getEmail());
        // Deduplicated per correction, not per account: a second correction is
        // a second thing the holder has to be told about. A timestamp would
        // merge two corrections landing in the same millisecond, which is the
        // one case the key is supposed to keep apart.
        String dedupKey = "profile_updated:" + user.getId() + ":" + UUID.randomUUID();
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_PROFILE_UPDATED,
                args, dedupKey);

        return adminUserQueryService.getUser(actor, userId);
    }

    @Transactional
    public UserAdminDetailResponse disable(AuthenticatedUser actor, UUID userId, String reason, String ip) {
        User user = userRepository.findByPublicId(userId).orElseThrow(AdminUserService::userNotFound);
        if (actor.id().equals(user.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_SELF_DISABLE_FORBIDDEN,
                    "비활성화할 수 없는 계정입니다", "본인 계정은 비활성화할 수 없습니다.");
        }
        if (user.getStatus() != UserStatus.ACTIVE && user.getStatus() != UserStatus.PENDING_VERIFICATION) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_INVALID_STATE,
                    "비활성화할 수 없는 계정입니다",
                    user.getStatus() == UserStatus.DISABLED
                            ? "이미 비활성화된 계정입니다." : "탈퇴한 계정은 비활성화할 수 없습니다.");
        }

        Instant now = Instant.now();
        UserStatus fromStatus = user.getStatus();
        user.setStatus(UserStatus.DISABLED);
        user.disable(reason, now);
        user.bumpTokenVersion();
        // Access tokens die on the version bump; revoke refresh tokens too so a
        // token left unused during the disable window cannot resurrect the session
        // after a later enable.
        refreshTokenService.revokeAllForUser(user.getId());
        userStatusChangeRepository.save(new UserStatusChange(user.getId(), fromStatus,
                UserStatus.DISABLED, actor.id(), reason));

        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_DISABLE,
                "user", user.getPublicId(), Map.of("reason", reason, "fromStatus", fromStatus.name()), ip);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("userId", user.getId());
        args.put("userEmail", user.getEmail());
        args.put("reason", reason);
        String dedupKey = "account_disabled:" + user.getId() + ":" + user.getTokenVersion();
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_DISABLED, args, dedupKey);
        Map<String, Object> adminArgs = new LinkedHashMap<>(args);
        adminArgs.put("admin", true);
        notificationService.publish(
                notificationService.sysAdminIds().stream().filter(id -> !id.equals(user.getId())).toList(),
                NotificationEvent.ACCOUNT_DISABLED, adminArgs, dedupKey);

        return adminUserQueryService.getUser(actor, userId);
    }

    @Transactional
    public UserAdminDetailResponse enable(AuthenticatedUser actor, UUID userId, String ip) {
        User user = userRepository.findByPublicId(userId).orElseThrow(AdminUserService::userNotFound);
        if (user.getStatus() != UserStatus.DISABLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_NOT_DISABLED,
                    "활성화할 수 없는 계정입니다",
                    "비활성화 상태의 계정만 활성화할 수 있습니다. 탈퇴한 계정은 되돌릴 수 없습니다.");
        }

        // Restore the pre-disable status (ACTIVE or PENDING_VERIFICATION) so
        // enable never bypasses email verification.
        UserStatus restored = userStatusChangeRepository
                .findFirstByUserIdAndToStatusOrderByChangedAtDescIdDesc(user.getId(), UserStatus.DISABLED)
                .map(UserStatusChange::getFromStatus)
                .orElse(UserStatus.ACTIVE);
        user.setStatus(restored);
        user.clearDisabled();
        userStatusChangeRepository.save(new UserStatusChange(user.getId(), UserStatus.DISABLED,
                restored, actor.id(), null));

        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_ENABLE,
                "user", user.getPublicId(), Map.of("toStatus", restored.name()), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_ENABLED,
                Map.of("userId", user.getId(), "userEmail", user.getEmail()),
                "account_enabled:" + user.getId() + ":" + Instant.now().toEpochMilli());

        return adminUserQueryService.getUser(actor, userId);
    }

    private static ApiException userNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "사용자를 찾을 수 없습니다", "해당 ID의 사용자가 존재하지 않습니다.");
    }
}
