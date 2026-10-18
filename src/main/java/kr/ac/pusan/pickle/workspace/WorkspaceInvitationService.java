package kr.ac.pusan.pickle.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock;
    private final kr.ac.pusan.pickle.access.ActorAccessImpacts accessImpacts;

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

    private final WorkspaceTerminationImpactService terminationImpacts;
    private final LifecycleActors lifecycleActors;
    private final kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions;
    private final WorkspaceRepository workspaces;
    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final InvitationLocks invitationLocks;
    private final InvitationWriter invitationWriter;

    private final kr.ac.pusan.pickle.request.RequestRecipientService recipientService;

    public WorkspaceInvitationService(kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock, kr.ac.pusan.pickle.access.ActorAccessImpacts accessImpacts, WorkspaceService workspaceService,
            WorkspaceInvitationRepository invitationRepository,
            UserRepository userRepository,
            RateLimitService rateLimitService, AuditService auditService, InvitationLocks invitationLocks,
            InvitationWriter invitationWriter,
            kr.ac.pusan.pickle.request.RequestRecipientService recipientService, WorkspaceTerminationImpactService terminationImpacts,
            LifecycleActors lifecycleActors, WorkspaceRepository workspaces, kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions) {
        this.terminationImpacts = terminationImpacts; this.lifecycleActors = lifecycleActors; this.workspaces = workspaces;
        this.permissions = permissions; this.administrationLock = administrationLock; this.accessImpacts = accessImpacts;
        this.invitationWriter = invitationWriter;
        this.recipientService = recipientService;
        this.workspaceService = workspaceService;
        this.invitationRepository = invitationRepository;
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
        return invite(actor, publicWorkspaceId, request, ip, false);
    }

    @Transactional
    public InviteWorkspaceMembersResponse invite(AuthenticatedUser actor, UUID publicWorkspaceId,
            InviteWorkspaceMembersRequest request, String ip, boolean support) {
        administrationLock.acquire();
        if (support) { actor = permissions.currentAfterLock(actor); }
        workspaceService.requireActiveActor(actor);
        String subject = "user:" + actor.id();
        rateLimitService.hit(INVITE_CALL_SCOPE, subject, INVITE_CALLS_PER_MINUTE);
        Workspace workspace = workspaceService.findWorkspace(publicWorkspaceId);
        if (support) { workspaceService.requireSupportWork(actor, workspace,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.WORKSPACE_INVITATIONS_MANAGE); }
        workspaceService.requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자(OWNER)만 구성원을 초대할 수 있습니다.",
                "PERSONAL 워크스페이스에는 구성원을 초대할 수 없습니다.", support);
        List<Invitee> invitees = normalize(request.entries());
        var impact = invitationImpact(actor, workspace, invitees);
        kr.ac.pusan.pickle.access.ActorAccessImpacts.requireConfirmed(impact, request.confirmation());
        invitationWriter.chargeEntries(actor.id(), invitees.size());

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
            results[i] = inviteOne(actor, workspace, invitee, ip, support, impact, request.confirmation());
        }
        return new InviteWorkspaceMembersResponse(List.of(results));
    }

    private WorkspaceInvitationResult inviteOne(AuthenticatedUser actor, Workspace workspace, Invitee invitee,
            String ip, boolean support, kr.ac.pusan.pickle.access.dto.ActorAccessImpact impact,
            kr.ac.pusan.pickle.access.dto.AccessConfirmation confirmation) {
        Map<String, Object> auditExtra = new java.util.LinkedHashMap<>(kr.ac.pusan.pickle.access.ActorAccessImpacts.auditDetail(impact, confirmation));
        if (support) { auditExtra.put("adminIntervention", true); }
        InvitationWriter.Placement placement =
                invitationWriter.place(actor, workspace, invitee.email(), invitee.studentNo(), ip, auditExtra);
        return invitee.result(placement.outcome(), placement.invitationId(),
                placement.member() != null ? placement.member().getPublicId() : null);
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
        return pending(workspace.getId());
    }

    /**
     * A workspace's open invitations, oldest first, with no gate of its own.
     * For callers that have already decided the reader may see them: the
     * owner path above, and the admin read an approver uses to name invitees
     * as the recipients of a request.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse> pending(long workspaceId) {
        List<WorkspaceInvitation> pending = invitationRepository
                .findByWorkspaceIdAndStatusOrderByCreatedAtAscIdAsc(workspaceId,
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
        cancel(actor, publicWorkspaceId, publicInvitationId, ip, false, null);
    }

    @Transactional
    public void cancel(AuthenticatedUser actor, UUID publicWorkspaceId, UUID publicInvitationId, String ip, boolean support) {
        cancel(actor, publicWorkspaceId, publicInvitationId, ip, support, null);
    }

    @Transactional
    public void cancel(AuthenticatedUser actor, UUID publicWorkspaceId, UUID publicInvitationId, String ip, boolean support,
            kr.ac.pusan.pickle.workspace.dto.@Nullable TerminationConfirmation confirmation) {
        administrationLock.acquire();
        actor = lifecycleActors.currentAfterAdministrationLock(actor);
        workspaceService.requireActiveActor(actor);
        Workspace workspace = workspaceService.findWorkspace(publicWorkspaceId);
        if (support) { workspaceService.requireSupportWork(actor, workspace,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.WORKSPACE_INVITATIONS_MANAGE); }
        workspaceService.requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자만 초대를 관리할 수 있습니다.", "개인 워크스페이스에는 초대가 없습니다.", support);
        WorkspaceInvitation invitation = invitationRepository.findWithLockByPublicId(publicInvitationId)
                .filter(found -> found.getWorkspaceId().equals(workspace.getId()))
                .filter(found -> found.getStatus() == WorkspaceInvitationStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.WORKSPACE_INVITATION_NOT_FOUND,
                        "초대를 찾을 수 없습니다", "대기 중인 초대가 아닙니다. 이미 수락되었거나 취소되었을 수 있습니다."));
        workspaces.findByIdForUpdate(workspace.getId()).orElseThrow();
        recipientService.lockForInvitationTermination(invitation.getId());
        var impact = terminationImpacts.lockedSnapshot(actor, workspace, invitation, support);
        TerminationConfirmations.requireConfirmed(impact.fingerprint(), confirmation);
        invitation.cancel(actor.id(), Instant.now());
        // A request that reserved a resource for this invitee stops waiting.
        recipientService.onInvitationCanceled(invitation);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_INVITATION_CANCEL,
                "workspace", workspace.getPublicId(),
                Map.of("invitationId", invitation.getPublicId(),
                        "kind", invitation.getInviteeEmail() != null ? "email" : "studentNo", "adminIntervention", support), ip);
    }

    @Transactional
    public kr.ac.pusan.pickle.access.dto.ActorAccessImpact previewInvite(AuthenticatedUser actor, UUID workspaceId,
            InviteWorkspaceMembersRequest request, boolean support) {
        administrationLock.acquire();
        if (support) { actor = permissions.currentAfterLock(actor); }
        Workspace workspace = workspaceService.findWorkspace(workspaceId);
        if (support) { workspaceService.requireSupportWork(actor, workspace,
                kr.ac.pusan.pickle.security.institution.InstitutionWork.WORKSPACE_INVITATIONS_MANAGE); }
        workspaceService.requireOwnerForMemberManagement(workspace, actor, "소유자만 초대할 수 있습니다.",
                "개인 워크스페이스에는 초대할 수 없습니다.", support);
        return invitationImpact(actor, workspace, normalize(request.entries()));
    }

    private kr.ac.pusan.pickle.access.dto.ActorAccessImpact invitationImpact(AuthenticatedUser actor,
            Workspace workspace, List<Invitee> invitees) {
        User holder = userRepository.findById(actor.id()).orElseThrow();
        boolean self = invitees.stream().anyMatch(i -> i.email() != null && i.email().equalsIgnoreCase(holder.getEmail())
                || i.studentNo() != null && holder.getStudentNo() != null && i.studentNo().equalsIgnoreCase(holder.getStudentNo()));
        String binding = "INVITE:" + workspace.getPublicId() + ':' + invitees.stream().map(Invitee::key).toList();
        return self ? accessImpacts.membership(actor, workspace, WorkspaceMemberRole.MEMBER, binding, List.of())
                : accessImpacts.bind(actor, binding, List.of(), List.of());
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
