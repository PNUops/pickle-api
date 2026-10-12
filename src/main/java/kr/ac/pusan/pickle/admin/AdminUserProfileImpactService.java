package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminUserProfileImpactRequest;
import kr.ac.pusan.pickle.admin.dto.AdminUserProfileImpactResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserStatusChangeRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observes claim conditions without taking claim locks, writing rows, or enqueuing work. */
@Service
public class AdminUserProfileImpactService {
    private final AdminUserSupportQueryService support;
    private final AdminUserProfileChanges profiles;
    private final UserStatusChangeRepository statusChanges;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminUserProfileImpactService(AdminUserSupportQueryService support, AdminUserProfileChanges profiles,
            UserStatusChangeRepository statusChanges, JdbcTemplate jdbc, Clock clock) {
        this.support = support;
        this.profiles = profiles;
        this.statusChanges = statusChanges;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminUserProfileImpactResponse preview(AuthenticatedUser actor, UUID userId,
            AdminUserProfileImpactRequest request) {
        User user = support.requireUser(actor, userId);
        boolean enabling = request.action() == AdminUserProfileImpactRequest.AdminUserProfileImpactAction.ENABLE;
        String studentNo = user.getStudentNo();
        UserStatus status = user.getStatus();
        if (enabling) {
            if (request.profile() != null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("profile", "활성화 미리보기에는 프로필을 보내지 마세요.")));
            }
            if (status != UserStatus.DISABLED) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ACCOUNT_NOT_DISABLED,
                        "활성화할 수 없는 계정입니다", "비활성화 상태의 계정만 활성화할 수 있습니다. 탈퇴한 계정은 되돌릴 수 없습니다.");
            }
            status = AdminUserProfileChanges.restoredStatus(user, statusChanges);
        } else {
            if (request.profile() == null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("profile", "정정할 프로필을 보내 주세요.")));
            }
            studentNo = profiles.candidate(user, request.profile()).studentNo();
        }
        boolean changed = studentNo == null ? user.getStudentNo() != null
                : user.getStudentNo() == null || !studentNo.equalsIgnoreCase(user.getStudentNo());
        boolean triggered = enabling || changed && studentNo != null;
        boolean claimsPossible = status == UserStatus.ACTIVE && triggered;
        return new AdminUserProfileImpactResponse(request.action(), userId, clock.instant(), status, claimsPossible,
                changed, triggered ? invitations(user, enabling ? user.getEmail() : null, studentNo, claimsPossible) : List.of());
    }

    private List<AdminUserProfileImpactResponse.AdminUserProfileInvitationImpact> invitations(User user, @Nullable String email,
            @Nullable String studentNo, boolean claimsPossible) {
        List<InvitationRow> rows = jdbc.query("""
                select i.id, i.public_id, w.public_id as workspace_public_id, w.name, w.kind, i.role,
                       case when lower(i.invitee_email) = lower(cast(? as text)) then 'EMAIL' else 'STUDENT_NO' end as matched_by,
                       exists(select 1 from workspace_members m where m.workspace_id = w.id and m.user_id = ?) as already_member
                  from workspace_invitations i join workspaces w on w.id = i.workspace_id
                 where i.status = 'PENDING' and w.deleted_at is null and
                       ((cast(? as text) is not null and lower(i.invitee_email) = lower(cast(? as text))) or
                        (cast(? as text) is not null and upper(i.invitee_student_no) = upper(cast(? as text))))
                 order by case when lower(i.invitee_email) = lower(cast(? as text)) then 0 else 1 end, i.id
                """, (rs, row) -> new InvitationRow(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                        rs.getObject("workspace_public_id", UUID.class), rs.getString("name"),
                        WorkspaceKind.valueOf(rs.getString("kind")), WorkspaceMemberRole.valueOf(rs.getString("role")),
                        rs.getString("matched_by"), rs.getBoolean("already_member")),
                email, user.getId(), email, email, studentNo, studentNo, email);
        List<AdminUserProfileImpactResponse.AdminUserProfileInvitationImpact> result = new ArrayList<>();
        // Claim processes invitations in identifier order: an earlier invitation can
        // name a recipient before a second invitation of the same request is reached.
        java.util.Set<Long> namedRequests = new java.util.HashSet<>();
        for (InvitationRow invitation : rows) {
            List<AdminUserProfileImpactResponse.AdminUserProfileRecipientImpact> recipients = jdbc.query("""
                    select r.id, r.public_id, o.public_id as org_public_id, r.resource_type, r.status, rr.status as recipient_status,
                           rv.granted_end_date, exists(select 1 from request_recipients other
                               where other.request_id = r.id and other.id <> rr.id and other.user_id = ?) as duplicate
                      from request_recipients rr join requests r on r.id = rr.request_id join orgs o on o.id = r.org_id
                      left join request_reviews rv on rv.request_id = r.id
                     where rr.invitation_id = ? and rr.status = 'PENDING_JOIN' order by rr.id
                    """, (rs, row) -> {
                        long requestId = rs.getLong("id");
                        LocalDate end = rs.getObject("granted_end_date", LocalDate.class);
                        RequestStatus requestStatus = RequestStatus.valueOf(rs.getString("status"));
                        RequestRecipientStatus actual = RequestRecipientStatus.valueOf(rs.getString("recipient_status"));
                        boolean duplicate = rs.getBoolean("duplicate") || namedRequests.contains(requestId);
                        RequestRecipientStatus projected = !claimsPossible ? actual : duplicate
                                ? RequestRecipientStatus.SKIPPED_INELIGIBLE
                                : requestStatus == RequestStatus.APPROVED && end != null && end.isBefore(ClockConfig.todayKst(clock))
                                ? RequestRecipientStatus.SKIPPED_EXPIRED : RequestRecipientStatus.QUEUED;
                        if (claimsPossible && !duplicate) { namedRequests.add(requestId); }
                        return new AdminUserProfileImpactResponse.AdminUserProfileRecipientImpact(rs.getObject("public_id", UUID.class),
                                rs.getObject("org_public_id", UUID.class),
                                ResourceType.valueOf(rs.getString("resource_type")), requestStatus, actual, projected, end);
                    }, user.getId(), invitation.internalId());
            result.add(new AdminUserProfileImpactResponse.AdminUserProfileInvitationImpact(invitation.id(), invitation.workspaceId(),
                    invitation.name(), invitation.kind(), invitation.role(), WorkspaceInvitationStatus.PENDING,
                    invitation.matchedBy(), null, invitation.alreadyMember(), recipients));
        }
        return List.copyOf(result);
    }

    private record InvitationRow(long internalId, UUID id, UUID workspaceId, String name, WorkspaceKind kind,
            WorkspaceMemberRole role, String matchedBy, boolean alreadyMember) { }
}
