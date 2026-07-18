package kr.ac.pusan.pickle.user;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RefreshTokenRepository;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
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
 * transaction: sessions, memberships, the PERSONAL group, and SSH keys.
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final GroupMemberRepository groupMemberRepository;
    private final VmRepository vmRepository;
    private final UserSshKeyRepository userSshKeyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserStatusChangeRepository userStatusChangeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            GroupMemberRepository groupMemberRepository, VmRepository vmRepository,
            UserSshKeyRepository userSshKeyRepository, RefreshTokenRepository refreshTokenRepository,
            UserStatusChangeRepository userStatusChangeRepository, AuditService auditService,
            NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.groupMemberRepository = groupMemberRepository;
        this.vmRepository = vmRepository;
        this.userSshKeyRepository = userSshKeyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userStatusChangeRepository = userStatusChangeRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public MessageResponse withdraw(long userId, String password, String ip) {
        User user = userRepository.findById(userId).orElseThrow(AccountService::sessionUserGone);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_WITHDRAW,
                    "user", user.getId(), Map.of("result", "mismatch"), ip);
            throw passwordMismatch();
        }
        // W2-A: verify TOTP when user_mfa exists (totpCode/recoveryCode ignored until then).

        List<GroupMember> liveMemberships = groupMemberRepository.findWithGroupByUserId(user.getId()).stream()
                .filter(member -> member.getGroup().getDeletedAt() == null)
                .toList();
        checkWithdrawBlockers(liveMemberships);

        Instant now = Instant.now();
        UserStatus fromStatus = user.getStatus();
        user.setStatus(UserStatus.WITHDRAWN);
        user.setWithdrawnAt(now);
        user.bumpTokenVersion();

        refreshTokenRepository.deleteByUserId(user.getId());
        userSshKeyRepository.deleteByUserId(user.getId());
        personalGroup(liveMemberships).ifPresent(group -> group.softDelete(user.getId(), now));
        groupMemberRepository.deleteByUserId(user.getId());
        userStatusChangeRepository.save(new UserStatusChange(user.getId(), fromStatus,
                UserStatus.WITHDRAWN, user.getId(), null));

        auditService.recordAfterCommit(user.getId(), user.getRole().name(), AuditService.ACCOUNT_WITHDRAW,
                "user", user.getId(), Map.of("email", user.getEmail()), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_WITHDRAWN,
                Map.of("userId", user.getId(), "userEmail", user.getEmail()),
                "account_withdrawn:" + user.getId());
        return new MessageResponse("탈퇴가 완료되었습니다. 그동안 이용해 주셔서 감사합니다.");
    }

    private void checkWithdrawBlockers(List<GroupMember> liveMemberships) {
        for (GroupMember member : liveMemberships) {
            Group group = member.getGroup();
            if (member.getRole() == GroupMemberRole.OWNER && group.getKind() != GroupKind.PERSONAL
                    && groupMemberRepository.countByGroupIdAndRole(group.getId(), GroupMemberRole.OWNER) == 1
                    && vmRepository.countActiveByGroupId(group.getId(), VmStatus.DELETED) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_SOLE_OWNER_OF_ACTIVE_GROUP,
                        "탈퇴할 수 없습니다",
                        "삭제되지 않은 VM을 보유한 그룹의 유일한 소유자입니다. 소유권을 이전하거나 VM을 먼저 삭제해 주세요.");
            }
        }
        personalGroup(liveMemberships).ifPresent(group -> {
            if (vmRepository.countActiveByGroupId(group.getId(), VmStatus.DELETED) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_HAS_ACTIVE_VMS,
                        "탈퇴할 수 없습니다", "사용 중인 VM이 남아 있습니다. VM을 먼저 삭제한 뒤 다시 시도해 주세요.");
            }
        });
    }

    private static java.util.Optional<Group> personalGroup(List<GroupMember> liveMemberships) {
        return liveMemberships.stream()
                .map(GroupMember::getGroup)
                .filter(group -> group.getKind() == GroupKind.PERSONAL)
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
