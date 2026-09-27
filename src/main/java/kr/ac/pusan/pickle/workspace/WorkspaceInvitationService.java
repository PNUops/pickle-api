package kr.ac.pusan.pickle.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest.WorkspaceInvitationEntry;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersResponse;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersResponse.WorkspaceInvitationResult;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse.WorkspaceInvitationInviter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk invitation into a workspace by email or 학번 (contract tag
 * {@code workspaces}). Adding one person is an invitation with one entry;
 * there is no separate member-add operation.
 *
 * <p>Each entry either adds an ACTIVE account at once or leaves a PENDING
 * invitation that {@link InvitationClaimService} turns into a membership when
 * the matching account becomes usable.</p>
 */
@Service
public class WorkspaceInvitationService {

    /** Calls per actor per minute. */
    static final int INVITE_CALLS_PER_MINUTE = 10;
    /** Entries per actor per hour, counted across calls. */
    static final int INVITE_ENTRIES_PER_HOUR = 500;
    static final String INVITE_CALL_SCOPE = "workspace_invite";
    static final String INVITE_ENTRY_SCOPE = "workspace_invite_entries";
    /**
     * Checked after stripping, not by bean validation on the raw field: a
     * pasted line with a stray space is still the address it names.
     */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final InvitationLocks invitationLocks;

    public WorkspaceInvitationService(WorkspaceService workspaceService,
            WorkspaceInvitationRepository invitationRepository,
            WorkspaceMemberRepository workspaceMemberRepository, UserRepository userRepository,
            RateLimitService rateLimitService, AuditService auditService, InvitationLocks invitationLocks) {
        this.workspaceService = workspaceService;
        this.invitationRepository = invitationRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.invitationLocks = invitationLocks;
    }

    /**
     * Invites up to 200 people in one request, answering per entry in request
     * order.
     *
     * <p>Races are absorbed per entry rather than per request: both inserts
     * are {@code on conflict do nothing}, so an entry that loses to a
     * concurrent invitation or membership reads as ALREADY_INVITED or
     * ALREADY_MEMBER and the transaction carries on. That is simpler than a
     * savepoint per entry and cannot leave the transaction aborted.</p>
     *
     * <p>Rate limits: the old single-member add answered "no such user" for
     * any address, which made it an unlimited probe of who has an account.
     * Calls are capped per minute and entries per hour; the hourly budget is
     * charged for the whole request up front, after validation, and a request
     * that does not fit is refused without spending any of it.</p>
     */
    @Transactional
    public InviteWorkspaceMembersResponse invite(AuthenticatedUser actor, UUID publicWorkspaceId,
            InviteWorkspaceMembersRequest request, String ip) {
        String subject = "user:" + actor.id();
        rateLimitService.hit(INVITE_CALL_SCOPE, subject, INVITE_CALLS_PER_MINUTE);
        Workspace workspace = workspaceService.findWorkspace(publicWorkspaceId);
        workspaceService.requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자(OWNER)만 구성원을 초대할 수 있습니다.",
                "PERSONAL 워크스페이스에는 구성원을 초대할 수 없습니다.");
        List<Invitee> invitees = normalize(request.entries());
        try {
            rateLimitService.hitHourly(INVITE_ENTRY_SCOPE, subject, INVITE_ENTRIES_PER_HOUR,
                    invitees.size());
        } catch (ApiException limited) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                    "요청이 너무 많습니다", "한 시간에 초대할 수 있는 인원(" + INVITE_ENTRIES_PER_HOUR
                            + "명)을 넘었습니다. 잠시 후 다시 시도해 주세요.",
                    null, limited.getRetryAfterSeconds());
        }

        // The first occurrence of a person is the one that counts; later ones
        // are duplicates, whatever order the work below runs in.
        WorkspaceInvitationResult[] results = new WorkspaceInvitationResult[invitees.size()];
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < invitees.size(); i++) {
            Invitee invitee = invitees.get(i);
            if (firstIndex.putIfAbsent(invitee.key(), i) != null) {
                results[i] = invitee.result(WorkspaceInvitationOutcome.DUPLICATE_IN_REQUEST, null, null);
            }
        }
        // Worked in key order, answered in request order. Two co-owners sending
        // overlapping lists in different orders would otherwise each hold one
        // row the other's insert waits on, and one request would die as a
        // deadlock. The same order is what the identifier locks require.
        List<Integer> order = new ArrayList<>(firstIndex.values());
        order.sort(Comparator.comparing(i -> invitees.get(i).key()));
        for (int i : order) {
            Invitee invitee = invitees.get(i);
            invitationLocks.lock(invitee.key());
            results[i] = inviteOne(actor, workspace, invitee, ip);
        }
        return new InviteWorkspaceMembersResponse(List.of(results));
    }

    private WorkspaceInvitationResult inviteOne(AuthenticatedUser actor, Workspace workspace, Invitee invitee,
            String ip) {
        // Enumeration: ADDED versus INVITED tells the owner whether an ACTIVE
        // account holds this email or 학번. That bit cannot be hidden, because
        // adding an existing account at once is the point of the feature. What
        // is hidden is everything else: an account in any other state answers
        // INVITED exactly as a missing one does, and ADDED carries the account
        // id only, no name or profile field.
        Optional<User> active = (invitee.email() != null
                ? userRepository.findByEmail(invitee.email())
                : userRepository.findByStudentNoIgnoreCase(invitee.studentNo()))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (active.isPresent()) {
            User target = active.get();
            int inserted = workspaceMemberRepository.insertMemberIfAbsent(workspace.getId(), target.getId(),
                    WorkspaceMemberRole.MEMBER.name());
            if (inserted == 0) {
                return invitee.result(WorkspaceInvitationOutcome.ALREADY_MEMBER, null, null);
            }
            auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_ADD,
                    "workspace", workspace.getPublicId(),
                    Map.of("userId", target.getPublicId(), "email", target.getEmail(),
                            "role", WorkspaceMemberRole.MEMBER.name(), "viaInvitation", false, "bulk", true),
                    ip);
            return invitee.result(WorkspaceInvitationOutcome.ADDED, null, target.getPublicId());
        }

        UUID invitationId = UUID.randomUUID();
        int inserted = invitationRepository.insertPendingIfAbsent(invitationId, workspace.getId(),
                invitee.email(), invitee.studentNo(), WorkspaceMemberRole.MEMBER.name(), actor.id());
        if (inserted == 0) {
            UUID existing = (invitee.email() != null
                    ? invitationRepository.findPendingIdByEmail(workspace.getId(), invitee.email())
                    : invitationRepository.findPendingIdByStudentNo(workspace.getId(), invitee.studentNo()))
                    .orElse(null);
            return invitee.result(WorkspaceInvitationOutcome.ALREADY_INVITED, existing, null);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_INVITATION_CREATE,
                "workspace", workspace.getPublicId(),
                Map.of("invitationId", invitationId, "kind", invitee.email() != null ? "email" : "studentNo"),
                ip);
        return invitee.result(WorkspaceInvitationOutcome.INVITED, invitationId, null);
    }

    /**
     * The workspace's open invitations, oldest first. OWNER only, like
     * invitation itself. Not a read-only transaction: the shared owner gate
     * locks the actor's membership row, which PostgreSQL refuses there.
     */
    @Transactional
    public List<WorkspaceInvitationResponse> list(AuthenticatedUser actor, UUID publicWorkspaceId) {
        Workspace workspace = workspaceService.findWorkspace(publicWorkspaceId);
        requireOwner(workspace, actor);
        List<WorkspaceInvitation> pending = invitationRepository
                .findByWorkspaceIdAndStatusOrderByCreatedAtAscIdAsc(workspace.getId(),
                        WorkspaceInvitationStatus.PENDING);
        Map<Long, User> inviters = userRepository
                .findAllById(pending.stream().map(WorkspaceInvitation::getInvitedBy).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return pending.stream().map(invitation -> {
            User inviter = inviters.get(invitation.getInvitedBy());
            return new WorkspaceInvitationResponse(invitation.getPublicId(), invitation.getInviteeEmail(),
                    invitation.getInviteeStudentNo(), invitation.getRole(), invitation.getCreatedAt(),
                    new WorkspaceInvitationInviter(inviter.getPublicId(), inviter.getName()));
        }).toList();
    }

    /** Cancels an open invitation. Answered or canceled ones are not found. */
    @Transactional
    public void cancel(AuthenticatedUser actor, UUID publicWorkspaceId, UUID publicInvitationId, String ip) {
        Workspace workspace = workspaceService.findWorkspace(publicWorkspaceId);
        requireOwner(workspace, actor);
        WorkspaceInvitation invitation = invitationRepository.findWithLockByPublicId(publicInvitationId)
                .filter(found -> found.getWorkspaceId().equals(workspace.getId()))
                .filter(found -> found.getStatus() == WorkspaceInvitationStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.WORKSPACE_INVITATION_NOT_FOUND,
                        "초대를 찾을 수 없습니다", "대기 중인 초대가 아닙니다. 이미 수락되었거나 취소되었을 수 있습니다."));
        invitation.cancel(actor.id(), Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_INVITATION_CANCEL,
                "workspace", workspace.getPublicId(),
                Map.of("invitationId", invitation.getPublicId(),
                        "kind", invitation.getInviteeEmail() != null ? "email" : "studentNo"), ip);
    }

    private void requireOwner(Workspace workspace, AuthenticatedUser actor) {
        workspaceService.requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자(OWNER)만 초대를 관리할 수 있습니다.",
                "PERSONAL 워크스페이스에는 초대가 없습니다.");
    }

    /**
     * Normalises every entry and rejects the request if any names nobody or
     * two people at once; the field is the entry's index so the client can
     * point at the line.
     */
    private static List<Invitee> normalize(List<WorkspaceInvitationEntry> entries) {
        List<Invitee> invitees = new ArrayList<>(entries.size());
        List<FieldValidationError> errors = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            WorkspaceInvitationEntry entry = entries.get(i);
            String field = "entries[" + i + "]";
            if (entry == null) {
                errors.add(new FieldValidationError(field, "이메일이나 학번 중 하나를 입력해 주세요."));
                continue;
            }
            String email = Texts.blankToNull(entry.email());
            String studentNo = Texts.blankToNull(entry.studentNo());
            if ((email == null) == (studentNo == null)) {
                errors.add(new FieldValidationError(field, email == null
                        ? "이메일이나 학번 중 하나를 입력해 주세요."
                        : "이메일과 학번 중 하나만 입력해 주세요."));
                continue;
            }
            if (email != null && !EMAIL_SHAPE.matcher(email).matches()) {
                errors.add(new FieldValidationError(field + ".email", "올바른 이메일 형식이 아닙니다."));
                continue;
            }
            if (studentNo != null && !ProfileValidator.isStudentNoFormat(studentNo)) {
                errors.add(new FieldValidationError(field + ".studentNo",
                        "학번 형식이 올바르지 않습니다. (영문·숫자·하이픈 4~20자)"));
                continue;
            }
            invitees.add(email != null
                    ? new Invitee(Texts.normalizeEmail(email), null)
                    : new Invitee(null, studentNo));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return invitees;
    }

    /** One normalised entry: exactly one of the two identifiers is set. */
    private record Invitee(@Nullable String email, @Nullable String studentNo) {

        /**
         * Dedup and lock key, folded the same way the unique indexes fold
         * each identifier; shared with {@link InvitationLocks}.
         */
        String key() {
            return email != null ? InvitationLocks.emailKey(email) : InvitationLocks.studentNoKey(studentNo);
        }

        WorkspaceInvitationResult result(WorkspaceInvitationOutcome outcome, @Nullable UUID invitationId,
                @Nullable UUID userId) {
            return new WorkspaceInvitationResult(email, studentNo, outcome, invitationId, userId);
        }
    }
}
