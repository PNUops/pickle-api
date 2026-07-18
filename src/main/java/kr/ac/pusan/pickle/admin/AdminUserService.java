package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.UserAdminDetailResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RefreshTokenService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
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

    public AdminUserService(UserRepository userRepository,
            UserStatusChangeRepository userStatusChangeRepository, AuditService auditService,
            NotificationService notificationService, AdminUserQueryService adminUserQueryService,
            MfaService mfaService, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.adminUserQueryService = adminUserQueryService;
        this.mfaService = mfaService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * SYS_ADMIN 2FA reset ({@code POST /admin/users/{userId}/mfa-reset}): clears
     * the target's enrollment + recovery codes after offline identity checks.
     * 404 if the user is unknown, 409 if they are not enrolled.
     */
    @Transactional
    public MessageResponse resetMfa(AuthenticatedUser actor, long userId, String ip) {
        User target = userRepository.findById(userId).orElseThrow(AdminUserService::userNotFound);
        mfaService.adminReset(actor.id(), actor.role().name(), target, ip);
        return new MessageResponse(
                "2단계 인증을 초기화했습니다. 사용자는 비밀번호로 로그인한 뒤 다시 등록해야 합니다.");
    }

    @Transactional
    public UserAdminDetailResponse disable(AuthenticatedUser actor, long userId, String reason, String ip) {
        User user = userRepository.findById(userId).orElseThrow(AdminUserService::userNotFound);
        if (actor.id().equals(userId)) {
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
                "user", user.getId(), Map.of("reason", reason, "fromStatus", fromStatus.name()), ip);
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
    public UserAdminDetailResponse enable(AuthenticatedUser actor, long userId, String ip) {
        User user = userRepository.findById(userId).orElseThrow(AdminUserService::userNotFound);
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
                "user", user.getId(), Map.of("toStatus", restored.name()), ip);
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
