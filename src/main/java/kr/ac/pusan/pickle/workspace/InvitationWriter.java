package kr.ac.pusan.pickle.workspace;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Adds a person to a workspace or leaves an invitation for them: the write
 * behind bulk invitation, shared with a request that names its recipients by
 * 학번. Neither gate nor transaction lives here; each caller has decided the
 * actor may do this and runs it inside its own transaction.
 *
 * <p>Callers lock every identifier first, in ascending key order, and only
 * then place each person. {@link InvitationLocks} says why the order matters.</p>
 */
@Component
public class InvitationWriter {

    /**
     * What one person came to. {@code member} is the account for ADDED and
     * ALREADY_MEMBER; {@code invitationId} the open invitation for INVITED and
     * ALREADY_INVITED.
     */
    public record Placement(WorkspaceInvitationOutcome outcome, @Nullable User member,
            @Nullable UUID invitationId) {
    }

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final InvitationLocks invitationLocks;

    public InvitationWriter(WorkspaceInvitationRepository invitationRepository,
            WorkspaceMemberRepository workspaceMemberRepository, UserRepository userRepository,
            RateLimitService rateLimitService, AuditService auditService, InvitationLocks invitationLocks) {
        this.invitationRepository = invitationRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.invitationLocks = invitationLocks;
    }

    /** The key a 학번 is deduplicated and locked by, folded as its unique index folds it. */
    public static String studentNoKey(String studentNo) {
        return InvitationLocks.studentNoKey(studentNo);
    }

    /**
     * Charges {@code entries} against the actor's hourly invitation budget, or
     * refuses with 429 having charged nothing. The budget is charged in its
     * own transaction, so it stays spent if the caller later rolls back.
     */
    public void chargeEntries(long actorId, int entries) {
        try {
            rateLimitService.hitHourly(WorkspaceInvitationService.INVITE_ENTRY_SCOPE, "user:" + actorId,
                    WorkspaceInvitationService.INVITE_ENTRIES_PER_HOUR, entries);
        } catch (ApiException limited) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                    "요청이 너무 많습니다", "한 시간에 초대할 수 있는 인원("
                            + WorkspaceInvitationService.INVITE_ENTRIES_PER_HOUR
                            + "명)을 넘었습니다. 잠시 후 다시 시도해 주세요.",
                    null, limited.getRetryAfterSeconds());
        }
    }

    /** Locks every 학번 in ascending key order. */
    public void lockStudentNos(Collection<String> studentNos) {
        studentNos.stream().map(InvitationLocks::studentNoKey).distinct().sorted()
                .forEach(invitationLocks::lock);
    }

    /** {@link #place} for one 학번, whose lock the caller already holds. */
    public Placement placeByStudentNo(AuthenticatedUser actor, Workspace workspace, String studentNo,
            String ip) {
        return place(actor, workspace, null, studentNo, ip);
    }

    /**
     * Adds the ACTIVE account the identifier names, or opens an invitation
     * for it. Exactly one of {@code email} and {@code studentNo} is set, and
     * the caller holds its lock.
     */
    Placement place(AuthenticatedUser actor, Workspace workspace, @Nullable String email,
            @Nullable String studentNo, String ip) {
        // Enumeration: ADDED versus INVITED tells the owner whether an ACTIVE
        // account holds this email or 학번. That bit cannot be hidden, because
        // adding an existing account at once is the point of the feature. What
        // is hidden is everything else: an account in any other state answers
        // INVITED exactly as a missing one does.
        Optional<User> active = (email != null
                ? userRepository.findByEmail(email)
                : userRepository.findByStudentNoIgnoreCase(studentNo))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (active.isPresent()) {
            User target = active.get();
            int inserted = workspaceMemberRepository.insertMemberIfAbsent(workspace.getId(), target.getId(),
                    WorkspaceMemberRole.MEMBER.name());
            invitationRepository.acceptPendingForMember(workspace.getId(), target.getId(),
                    target.getEmail(), target.getStudentNo());
            if (inserted == 0) {
                return new Placement(WorkspaceInvitationOutcome.ALREADY_MEMBER, target, null);
            }
            auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_ADD,
                    "workspace", workspace.getPublicId(),
                    Map.of("userId", target.getPublicId(), "email", target.getEmail(),
                            "role", WorkspaceMemberRole.MEMBER.name(), "viaInvitation", false, "bulk", true),
                    ip);
            return new Placement(WorkspaceInvitationOutcome.ADDED, target, null);
        }

        UUID invitationId = UUID.randomUUID();
        int inserted = invitationRepository.insertPendingIfAbsent(invitationId, workspace.getId(),
                email, studentNo, WorkspaceMemberRole.MEMBER.name(), actor.id());
        if (inserted == 0) {
            UUID existing = (email != null
                    ? invitationRepository.findPendingIdByEmail(workspace.getId(), email)
                    : invitationRepository.findPendingIdByStudentNo(workspace.getId(), studentNo))
                    .orElse(null);
            return new Placement(WorkspaceInvitationOutcome.ALREADY_INVITED, null, existing);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_INVITATION_CREATE,
                "workspace", workspace.getPublicId(),
                Map.of("invitationId", invitationId, "kind", email != null ? "email" : "studentNo"),
                ip);
        return new Placement(WorkspaceInvitationOutcome.INVITED, null, invitationId);
    }
}
