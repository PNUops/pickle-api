package kr.ac.pusan.pickle.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns open workspace invitations into memberships once the account they
 * name can hold one.
 *
 * <p>Two triggers, one per identifier. An email invitation is claimed when
 * the account with that address becomes ACTIVE ({@code AuthService.activateAccount},
 * which every activation path goes through). A 학번 invitation is claimed when
 * an ACTIVE account first holds that 학번: at activation if signup carried
 * one, otherwise when the holder saves it or an administrator sets it.</p>
 *
 * <p>Both run inside the caller's transaction, so a claim commits with the
 * activation or profile write that caused it, or not at all. Both are
 * idempotent: only PENDING rows are read, and each one is locked and flipped
 * to ACCEPTED in the same statement sequence. An invitation is ACCEPTED even
 * when the account was already a member, because what it asked for holds.
 * Removing the member later leaves it ACCEPTED: a rejoin never silently
 * restores access.</p>
 *
 * <p>Each claim first takes the {@link InvitationLocks} lock for its
 * identifier, the same one an owner's invitation takes, so an invitation
 * committed while the account was activating is still found. A 학번 claim
 * takes the account's email lock first, then the 학번 one: the owner's side
 * inserts a membership for an ACTIVE account while holding that account's
 * email or 학번 lock, and the membership's unique index is a second shared
 * resource. Taking the email lock first means a 학번 claim never holds a
 * lock the owner's email entry for the same account is waiting on.</p>
 *
 * <p>{@code actorId} names who caused the claim for the audit entry: the
 * account itself at activation or its own profile save, the administrator on
 * the correction path.</p>
 */
@Service
public class InvitationClaimService {

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuditService auditService;
    private final InvitationLocks invitationLocks;

    public InvitationClaimService(WorkspaceInvitationRepository invitationRepository,
            WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            AuditService auditService, InvitationLocks invitationLocks) {
        this.invitationRepository = invitationRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.auditService = auditService;
        this.invitationLocks = invitationLocks;
    }

    /**
     * Claims every open invitation addressed to the account's email. Reads
     * only what the entity already holds, so it is safe on an instance the
     * persistence context has detached.
     */
    @Transactional
    public void claimByEmail(User user) {
        claimByEmail(user, user.getId(), user.getRole().name());
    }

    @Transactional
    public void claimByEmail(User user, long actorId, String actorRole) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        invitationLocks.lock(InvitationLocks.emailKey(user.getEmail()));
        claimAll(user, invitationRepository.lockPendingByEmail(user.getEmail()), actorId, actorRole);
    }

    /** Claims every open invitation addressed to the account's 학번, if it holds one. */
    @Transactional
    public void claimByStudentNo(User user) {
        claimByStudentNo(user, user.getId(), user.getRole().name());
    }

    @Transactional
    public void claimByStudentNo(User user, long actorId, String actorRole) {
        String studentNo = user.getStudentNo();
        if (user.getStatus() != UserStatus.ACTIVE || studentNo == null) {
            return;
        }
        invitationLocks.lock(InvitationLocks.emailKey(user.getEmail()));
        invitationLocks.lock(InvitationLocks.studentNoKey(studentNo));
        claimAll(user, invitationRepository.lockPendingByStudentNo(studentNo), actorId, actorRole);
    }

    private void claimAll(User user, List<WorkspaceInvitation> invitations, long actorId, String actorRole) {
        Instant now = Instant.now();
        for (WorkspaceInvitation invitation : invitations) {
            claim(user, invitation, now, actorId, actorRole);
        }
    }

    /**
     * One invitation becomes one membership. This is the single place a
     * claim happens, so anything that must follow a claim belongs here.
     *
     * <p>An invitation into a deleted workspace is skipped and left as it is:
     * there is nothing to join, and the row records what the owner asked for
     * while the workspace existed.</p>
     */
    private void claim(User user, WorkspaceInvitation invitation, Instant now, long actorId,
            String actorRole) {
        Workspace workspace = workspaceRepository.findByIdAndDeletedAtIsNull(invitation.getWorkspaceId())
                .orElse(null);
        if (workspace == null) {
            return;
        }
        int inserted = workspaceMemberRepository.insertMemberIfAbsent(workspace.getId(), user.getId(),
                invitation.getRole().name());
        invitation.accept(user.getId(), now);
        if (inserted == 1) {
            auditService.recordAfterCommit(actorId, actorRole,
                    AuditService.WORKSPACE_MEMBER_ADD, "workspace", workspace.getPublicId(),
                    Map.of("userId", user.getPublicId(), "email", user.getEmail(),
                            "role", invitation.getRole().name(), "viaInvitation", true,
                            "invitationId", invitation.getPublicId()), null);
        }
    }
}
