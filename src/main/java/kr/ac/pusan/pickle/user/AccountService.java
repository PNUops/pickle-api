package kr.ac.pusan.pickle.user;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.auth.RefreshTokenRepository;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.sshkey.UserSshKeyRepository;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account withdrawal ({@code POST /me/withdraw}). WITHDRAWN is
 * permanent — the row is retained (privacy policy, 2026-07-08) so the same
 * email can never re-register. Withdrawal tears the account down in a single
 * transaction: sessions, memberships, the PERSONAL workspace, and SSH keys.
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final VmRepository vmRepository;
    private final UserSshKeyRepository userSshKeyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MfaService mfaService;

    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, VmRepository vmRepository,
            UserSshKeyRepository userSshKeyRepository, RefreshTokenRepository refreshTokenRepository,
            UserStatusChangeRepository userStatusChangeRepository,
            RateLimitService rateLimitService, AuditService auditService,
            NotificationService notificationService, MfaService mfaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
        this.vmRepository = vmRepository;
        this.userSshKeyRepository = userSshKeyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.mfaService = mfaService;
    }

    @Transactional
    public MessageResponse withdraw(long userId, String password, String totpCode, String recoveryCode,
            String ip) {
        User user = userRepository.findById(userId).orElseThrow(AccountService::sessionUserGone);
        // Password-only oracle for a hijacked session (the mismatch below throws
        // before the 2FA check): same dual-key window and shared lockout as login.
        rateLimitService.hit("withdraw:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("withdraw:acct", user.getEmail(), RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.checkLoginLock(user.getEmail(), ip);
        rateLimitService.checkCodeLock(user.getEmail(), ip);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimitService.registerLoginFailure(user.getEmail(), ip);
            auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_WITHDRAW,
                    "user", user.getId(), Map.of("result", "mismatch"), ip);
            throw passwordMismatch();
        }
        // 2FA-enrolled accounts must also present a valid TOTP or recovery code.
        // A wrong code escalates the code lockout, never the login one: someone who
        // lost their authenticator must not lock themselves out of logging in while
        // working through their recovery codes (same rule as the 2FA management
        // endpoints).
        if (mfaService.isEnrolled(user.getId())
                && !mfaService.verifyEnrolledCode(user.getId(), totpCode, recoveryCode)) {
            rateLimitService.registerCodeFailure(user.getEmail(), ip);
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_MFA_CODE_INVALID,
                    "본인 확인에 실패했습니다", "인증 코드를 다시 확인해 주세요.");
        }
        rateLimitService.clearLoginFailures(user.getEmail(), ip);
        rateLimitService.clearCodeFailures(user.getEmail(), ip);

        List<WorkspaceMember> liveMemberships = workspaceMemberRepository.findWithWorkspaceByUserId(user.getId()).stream()
                .filter(member -> member.getWorkspace().getDeletedAt() == null)
                .toList();
        checkWithdrawBlockers(liveMemberships);

        Instant now = Instant.now();
        UserStatus fromStatus = user.getStatus();
        user.setStatus(UserStatus.WITHDRAWN);
        user.setWithdrawnAt(now);
        user.bumpTokenVersion();

        refreshTokenRepository.deleteByUserId(user.getId());
        userSshKeyRepository.deleteByUserId(user.getId());
        personalWorkspace(liveMemberships).ifPresent(workspace -> workspace.softDelete(user.getId(), now));
        workspaceMemberRepository.deleteByUserId(user.getId());
        // Grants only ever name a member of the owning workspace, so they go
        // with the memberships rather than outliving the account.
        grantRepository.deleteByUserId(user.getId());
        userStatusChangeRepository.save(new UserStatusChange(user.getId(), fromStatus,
                UserStatus.WITHDRAWN, user.getId(), null));

        auditService.recordAfterCommit(user.getId(), user.getRole().name(), AuditService.ACCOUNT_WITHDRAW,
                "user", user.getId(), Map.of("email", user.getEmail()), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_WITHDRAWN,
                Map.of("userId", user.getId(), "userEmail", user.getEmail()),
                "account_withdrawn:" + user.getId());
        return new MessageResponse("탈퇴가 완료되었습니다. 그동안 이용해 주셔서 감사합니다.");
    }

    private void checkWithdrawBlockers(List<WorkspaceMember> liveMemberships) {
        for (WorkspaceMember member : liveMemberships) {
            Workspace workspace = member.getWorkspace();
            if (member.getRole() == WorkspaceMemberRole.OWNER && workspace.getKind() != WorkspaceKind.PERSONAL
                    && workspaceMemberRepository.countByWorkspaceIdAndRole(workspace.getId(), WorkspaceMemberRole.OWNER) == 1
                    && vmRepository.countActiveByWorkspaceId(workspace.getId(), VmStatus.DELETED) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE,
                        "탈퇴할 수 없습니다",
                        "삭제되지 않은 VM을 보유한 워크스페이스의 유일한 소유자입니다. 소유권을 이전하거나 VM을 먼저 삭제해 주세요.");
            }
        }
        personalWorkspace(liveMemberships).ifPresent(workspace -> {
            if (vmRepository.countActiveByWorkspaceId(workspace.getId(), VmStatus.DELETED) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_HAS_ACTIVE_VMS,
                        "탈퇴할 수 없습니다", "사용 중인 VM이 남아 있습니다. VM을 먼저 삭제한 뒤 다시 시도해 주세요.");
            }
        });
    }

    private static java.util.Optional<Workspace> personalWorkspace(List<WorkspaceMember> liveMemberships) {
        return liveMemberships.stream()
                .map(WorkspaceMember::getWorkspace)
                .filter(workspace -> workspace.getKind() == WorkspaceKind.PERSONAL)
                .findFirst();
    }

    private static ApiException passwordMismatch() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_PASSWORD_MISMATCH,
                "본인 확인에 실패했습니다", "비밀번호를 다시 확인해 주세요.");
    }

    private static ApiException sessionUserGone() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요.");
    }
}
