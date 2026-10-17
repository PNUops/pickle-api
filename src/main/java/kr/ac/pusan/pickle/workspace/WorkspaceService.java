package kr.ac.pusan.pickle.workspace;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.workspace.dto.CreateWorkspaceRequest;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceDetailResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceMemberResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceSummaryResponse;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceRequest;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.request.RequestStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace management (contract tag {@code workspaces}). Authorization
 * is resolved in this layer from a single membership row per request:
 * OWNER edits workspace info, manages members and appoints owners;
 * PERSONAL workspaces have immutable membership.
 */
@Service
public class WorkspaceService {
    private final kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock;
    private final kr.ac.pusan.pickle.access.ActorAccessImpacts accessImpacts;

    private final WorkspaceTerminationImpactService terminationImpacts;
    private final LifecycleActors lifecycleActors;
    private final jakarta.persistence.EntityManager entityManager;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final RequestRepository requestRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final List<ResourceTypeAdapter> resourceAdapters;

    private final kr.ac.pusan.pickle.request.RequestRecipientService recipientService;

    public WorkspaceService(kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock, kr.ac.pusan.pickle.access.ActorAccessImpacts accessImpacts, WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository,
            RequestRepository requestRepository, AuditService auditService, AuditIds auditIds,
            NotificationService notificationService, List<ResourceTypeAdapter> resourceAdapters,
            kr.ac.pusan.pickle.request.RequestRecipientService recipientService, WorkspaceTerminationImpactService terminationImpacts,
            LifecycleActors lifecycleActors, jakarta.persistence.EntityManager entityManager) {
        this.terminationImpacts = terminationImpacts; this.lifecycleActors = lifecycleActors; this.entityManager = entityManager;
        this.administrationLock = administrationLock; this.accessImpacts = accessImpacts;
        this.recipientService = recipientService;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.notificationService = notificationService;
        this.resourceAdapters = resourceAdapters;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummaryResponse> listMyWorkspaces(AuthenticatedUser actor) {
        List<WorkspaceMember> memberships = workspaceMemberRepository.findWithWorkspaceByUserId(actor.id());
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = workspaceMemberRepository
                .countMembersByWorkspaceIdIn(memberships.stream().map(m -> m.getWorkspace().getId()).toList())
                .stream()
                .collect(Collectors.toMap(WorkspaceMemberRepository.WorkspaceMemberCount::getWorkspaceId,
                        WorkspaceMemberRepository.WorkspaceMemberCount::getMemberCount));
        return memberships.stream()
                .map(m -> WorkspaceSummaryResponse.from(m, counts.getOrDefault(m.getWorkspace().getId(), 1L)))
                .toList();
    }

    @Transactional
    public WorkspaceDetailResponse create(AuthenticatedUser actor, CreateWorkspaceRequest request, String ip) {
        administrationLock.acquire();
        requireActiveActor(actor);
        Workspace workspace = workspaceRepository.save(new Workspace(request.kind().toWorkspaceKind(),
                request.name().strip(), normalize(request.description())));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, actor.id(), WorkspaceMemberRole.OWNER));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_CREATE,
                "workspace", workspace.getPublicId(),
                Map.of("kind", workspace.getKind().name(), "name", workspace.getName()), ip);
        return toDetail(workspace, WorkspaceMemberRole.OWNER);
    }

    @Transactional(readOnly = true)
    public WorkspaceDetailResponse get(AuthenticatedUser actor, UUID publicWorkspaceId) {
        Workspace workspace = findWorkspace(publicWorkspaceId);
        long workspaceId = workspace.getId();
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id())
                .orElseThrow(() -> accessDenied("워크스페이스 구성원만 조회할 수 있습니다."));
        return toDetail(workspace, membership.getRole());
    }

    @Transactional
    public WorkspaceDetailResponse update(AuthenticatedUser actor, UUID publicWorkspaceId,
            UpdateWorkspaceRequest request, String ip) {
        return update(actor, publicWorkspaceId, request, ip, false);
    }

    @Transactional
    public WorkspaceDetailResponse update(AuthenticatedUser actor, UUID publicWorkspaceId,
            UpdateWorkspaceRequest request, String ip, boolean support) {
        administrationLock.acquire();
        requireActiveActor(actor);
        if (support) { kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireWorkspaceAccessWrite(actor); }
        Workspace workspace = findWorkspace(publicWorkspaceId);
        long workspaceId = workspace.getId();
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id()).orElse(null);
        if (!support && (membership == null || membership.getRole() != WorkspaceMemberRole.OWNER)) {
            throw accessDenied("워크스페이스 소유자(OWNER)만 수정할 수 있습니다.");
        }
        String previousName = workspace.getName();
        String previousDescription = workspace.getDescription();
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("name", "수정할 값을 하나 이상 보내 주세요.")));
        }
        if (request.isNameSet()) {
            if (request.getName() == null || request.getName().isBlank()) {
                throw ApiException.validationFailed(List.of(
                        new FieldValidationError("name", "워크스페이스 이름은 비울 수 없습니다.")));
            }
            workspace.setName(request.getName().strip());
        }
        if (request.isDescriptionSet()) {
            workspace.setDescription(normalize(request.getDescription()));
        }
        if (request.isKindSet()) {
            applyKindChange(actor, workspace, request.getKind(), ip);
        }
        if (!java.util.Objects.equals(previousName, workspace.getName())
                || !java.util.Objects.equals(previousDescription, workspace.getDescription())) {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("previousName", previousName); detail.put("name", workspace.getName());
            detail.put("previousDescription", previousDescription); detail.put("description", workspace.getDescription());
            detail.put("adminIntervention", support);
            auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_UPDATE,
                    "workspace", workspace.getPublicId(), detail, ip);
        }
        return toDetail(workspace, membership == null ? null : membership.getRole());
    }

    /**
     * PERSONAL is excluded in both directions. Automatic creation, the
     * undeletable rule, the closed member list and the withdrawal cleanup all
     * decide by asking whether the kind is PERSONAL, so letting a workspace
     * leave or enter that value would quietly open every one of them.
     * The request type cannot name PERSONAL, which covers the inbound
     * direction; this covers the outbound one.
     */
    private void applyKindChange(AuthenticatedUser actor, Workspace workspace,
            @Nullable CreatableWorkspaceKind requested, String ip) {
        if (requested == null) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("kind", "워크스페이스 유형은 비울 수 없습니다.")));
        }
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("kind", "개인 워크스페이스는 유형을 바꿀 수 없습니다.")));
        }
        WorkspaceKind previous = workspace.getKind();
        WorkspaceKind next = requested.toWorkspaceKind();
        if (previous == next) {
            return;
        }
        workspace.changeKind(next);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_KIND_UPDATE,
                "workspace", workspace.getPublicId(),
                Map.of("previousKind", previous.name(), "kind", next.name()), ip);
    }

    @Transactional
    public WorkspaceMemberResponse updateMemberRole(AuthenticatedUser actor, UUID publicWorkspaceId,
            UUID publicUserId, UpdateWorkspaceMemberRequest request, String ip) {
        return updateMemberRole(actor, publicWorkspaceId, publicUserId, request, ip, false);
    }

    @Transactional
    public WorkspaceMemberResponse updateMemberRole(AuthenticatedUser actor, UUID publicWorkspaceId,
            UUID publicUserId, UpdateWorkspaceMemberRequest request, String ip, boolean support) {
        administrationLock.acquire();
        requireActiveActor(actor);
        Workspace workspace = findWorkspace(publicWorkspaceId);
        long workspaceId = workspace.getId();
        long targetUserId = memberUserId(publicUserId);
        requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자(OWNER)만 역할을 변경할 수 있습니다.", "PERSONAL 워크스페이스의 구성원은 변경할 수 없습니다.", support);
        // Locked before the count below. With one owner per workspace the count could
        // not be wrong; with several, two owners demoting each other concurrently
        // would both read two and commit, leaving a workspace nobody can administer.
        WorkspaceMember target = workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(WorkspaceService::memberNotFound);

        // Ownership is appointed and released rather than handed over: a workspace
        // may have several owners, so promoting somebody no longer demotes the
        // person doing it. What is protected is the last one — a workspace with no
        // owner has nobody who can add members or appoint a replacement.
        WorkspaceMemberRole previousRole = target.getRole();
        if (previousRole == WorkspaceMemberRole.OWNER && request.role() != WorkspaceMemberRole.OWNER
                && workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId,
                        WorkspaceMemberRole.OWNER) <= 1) {
            throw soleOwnerRemoval("유일한 소유자의 역할은 변경할 수 없습니다");
        }
        var impact = memberRoleImpact(actor, workspace, publicUserId, request.role());
        kr.ac.pusan.pickle.access.ActorAccessImpacts.requireConfirmed(impact, request.confirmation());
        target.setRole(request.role());

        // The audit is deferred to after commit (recordAfterCommit), so a
        // failure anywhere in this method leaves no audit row for a change that
        // never committed.
        User targetUser = userRepository.findById(targetUserId).orElseThrow(WorkspaceService::memberNotFound);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_UPDATE,
                "workspace", workspace.getPublicId(),
                memberAudit(targetUser.getPublicId(), previousRole, target.getRole(), support,
                        kr.ac.pusan.pickle.access.ActorAccessImpacts.auditDetail(impact, request.confirmation())), ip);
        return WorkspaceMemberResponse.from(target, targetUser);
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, UUID publicWorkspaceId, UUID publicUserId,
            String ip) {
        removeMember(actor, publicWorkspaceId, publicUserId, ip, false);
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, UUID publicWorkspaceId, UUID publicUserId,
            String ip, boolean support) {
        administrationLock.acquire();
        requireActiveActor(actor);
        if (support) { kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireWorkspaceAccessWrite(actor); }
        Workspace workspace = findWorkspace(publicWorkspaceId);
        long workspaceId = workspace.getId();
        long targetUserId = memberUserId(publicUserId);
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", "PERSONAL 워크스페이스의 구성원은 변경할 수 없습니다.");
        }
        WorkspaceMember actorMembership = workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspaceId, actor.id()).orElse(null);
        if (!support && actorMembership == null) {
            throw memberManageForbidden("구성원을 제거할 권한이 없습니다", "워크스페이스 소유자(OWNER)만 다른 구성원을 제거할 수 있습니다.");
        }
        boolean selfLeave = targetUserId == actor.id();
        if (!support && !selfLeave && actorMembership.getRole() != WorkspaceMemberRole.OWNER) {
            throw memberManageForbidden("구성원을 제거할 권한이 없습니다", "워크스페이스 소유자(OWNER)만 다른 구성원을 제거할 수 있습니다.");
        }

        WorkspaceMember target = workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(WorkspaceService::memberNotFound);
        if (target.getRole() == WorkspaceMemberRole.OWNER
                && workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceMemberRole.OWNER) <= 1) {
            throw soleOwnerRemoval("유일한 소유자는 나갈 수 없습니다");
        }

        workspaceMemberRepository.delete(target);
        // Leaving the workspace takes the access it carried: a grant may only name
        // a member of the owning workspace, so the rows go with the membership
        // rather than lying dormant until a rejoin silently restores them.
        int revokedGrants = revokeGrantsOnWorkspaceResources(workspaceId, targetUserId);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_REMOVE,
                "workspace", workspace.getPublicId(),
                Map.of("userId", auditIds.user(targetUserId), "previousRole", target.getRole().name(),
                        "selfLeave", selfLeave, "revokedGrants", revokedGrants, "adminIntervention", support), ip);
    }

    /**
     * Takes one person's grants on everything the workspace owns, whatever kind
     * of thing that is. Each resource type answers for itself, so a type added
     * later is revoked here without this method being touched.
     */
    private int revokeGrantsOnWorkspaceResources(long workspaceId, long userId) {
        int revoked = 0;
        for (ResourceTypeAdapter adapter : resourceAdapters) {
            List<Long> resourceIds = adapter.idsOwnedByWorkspace(workspaceId);
            if (!resourceIds.isEmpty()) {
                revoked += grantRepository.deleteUserGrantsOnResources(
                        AccessGranteeType.USER, userId, adapter.type(), resourceIds);
            }
        }
        return revoked;
    }

    /**
     * Soft-deletes a workspace (contract {@code deleteWorkspace}). OWNER only;
     * non-members are masked as 404 and members below OWNER get 403. PERSONAL
     * workspaces are never deletable (409), and a workspace still holding any
     * resource that is not fully destroyed is refused (409) — every type answers
     * for itself through {@link ResourceTypeAdapter#countLiveInWorkspace}, so a
     * type added later blocks deletion without this method being touched. The row
     * is kept (resource/audit history) with {@code deleted_at} stamped; ACTIVE
     * members are notified and the deletion is audited.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, UUID publicWorkspaceId, String ip) {
        delete(actor, publicWorkspaceId, ip, false, null);
    }

    @Transactional
    public void delete(AuthenticatedUser actor, UUID publicWorkspaceId, String ip, boolean support) {
        delete(actor, publicWorkspaceId, ip, support, null);
    }

    @Transactional
    public void delete(AuthenticatedUser actor, UUID publicWorkspaceId, String ip, boolean support,
            kr.ac.pusan.pickle.workspace.dto.@Nullable TerminationConfirmation confirmation) {
        administrationLock.acquire();
        actor = lifecycleActors.current(actor);
        requireActiveActor(actor);
        if (support) { kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireWorkspaceAccessWrite(actor); }
        Workspace workspace = workspaceRepository.findByPublicIdAndDeletedAtIsNull(publicWorkspaceId)
                .orElseThrow(WorkspaceService::workspaceNotFound);
        long workspaceId = workspace.getId();
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id()).orElse(null);
        if (!support && membership == null) { throw workspaceNotFound(); }
        if (!support && membership.getRole() != WorkspaceMemberRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    "워크스페이스를 삭제할 권한이 없습니다", "워크스페이스의 OWNER만 워크스페이스를 삭제할 수 있습니다.");
        }
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_PERSONAL_UNDELETABLE,
                    "워크스페이스를 삭제할 수 없습니다",
                    "개인 워크스페이스는 삭제할 수 없습니다. 계정 탈퇴 시에만 함께 정리됩니다.");
        }
        // Keep the existing request -> its recipients -> workspace -> remaining
        // recipients order, without mutating before the confirmed preimage is read.
        List<Request> pendingRequests = new java.util.ArrayList<>();
        for (Request pending : requestRepository.findByWorkspaceIdAndStatus(workspaceId, RequestStatus.SUBMITTED)) {
            Request locked = requestRepository.findWithLockByPublicId(pending.getPublicId()).orElse(null);
            if (locked == null) continue;
            entityManager.refresh(locked);
            if (locked.getStatus() != RequestStatus.SUBMITTED) continue;
            recipientService.lockUndecided(locked);
            pendingRequests.add(locked);
        }

        // Counted after the request rows are locked, not before: an approval
        // holding one of those locks has not committed its resource yet, so a
        // count taken first reads zero and then waits for that very approval —
        // and the workspace is soft-deleted around a live resource. Taking the
        // locks first means an approval that won the race has committed by the
        // time this count runs, and read-committed sees it.
        //
        // The workspace row is locked after the request rows and before the
        // count. After, because issuing a name takes the request row and then
        // the workspace row, and taking them the other way round here would
        // deadlock against it. Before the count, because a many-person request
        // makes its resources outside any request lock: its materializer holds
        // this row while it creates one, so this count either sees that
        // resource committed or runs first and the materializer then finds the
        // workspace deleted and its recipients closed.
        workspaceRepository.findByIdForUpdate(workspaceId).orElseThrow(WorkspaceService::workspaceNotFound);
        entityManager.refresh(workspace);
        recipientService.lockForWorkspaceTermination(workspaceId);
        var impact = terminationImpacts.lockedSnapshot(actor, workspace, null, support);
        TerminationConfirmations.requireConfirmed(impact.fingerprint(), confirmation);
        if (resourceAdapters.stream()
                .anyMatch(adapter -> adapter.countLiveInWorkspace(workspaceId) > 0)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_HAS_ACTIVE_VMS,
                    "워크스페이스를 삭제할 수 없습니다",
                    "워크스페이스에 삭제되지 않은 리소스가 있습니다. 리소스 삭제(파기 완료) 후 다시 시도해 주세요.");
        }

        for (Request pending : pendingRequests) {
            pending.setStatus(RequestStatus.CANCELED);
            recipientService.closeUndecided(pending);
            auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_CANCEL,
                    "request", pending.getPublicId(), Map.of("workspaceId", workspace.getPublicId(), "reason", "workspace_deleted"), ip);
        }
        // Recipients are resolved before the soft-delete flips visibility; the
        // membership rows themselves are kept (only the workspace row is stamped).
        List<Long> recipients = notificationService.workspaceMemberIds(workspaceId);
        // Approved many-person requests may still have people waiting for a
        // resource here, queued or not yet joined; none will be made now.
        recipientService.closeForDeletedWorkspace(workspaceId);
        workspace.softDelete(actor.id(), Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_DELETE,
                "workspace", workspace.getPublicId(),
                Map.of("kind", workspace.getKind().name(), "name", workspace.getName(), "adminIntervention", support), ip);
        notificationService.publish(recipients, NotificationEvent.WORKSPACE_DELETED,
                Map.of("workspaceId", workspace.getPublicId(), "workspaceName", workspace.getName()),
                "workspace_deleted:" + workspaceId);
    }

    /**
     * Member-management gate (invitations/role-change): actor must be OWNER and
     * the workspace must not be PERSONAL — both violations render 403
     * {@code WORKSPACE_MEMBER_MANAGE_FORBIDDEN} per contract. Package-private
     * so {@link WorkspaceInvitationService} applies the same gate; it locks the
     * actor's membership row and so must run inside the caller's transaction.
     */
    WorkspaceMember requireOwnerForMemberManagement(Workspace workspace, AuthenticatedUser actor,
            String notOwnerDetail, String personalDetail) {
        return requireOwnerForMemberManagement(workspace, actor, notOwnerDetail, personalDetail, false);
    }

    @Nullable WorkspaceMember requireOwnerForMemberManagement(Workspace workspace, AuthenticatedUser actor,
            String notOwnerDetail, String personalDetail, boolean support) {
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", personalDetail);
        }
        if (support) { kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireWorkspaceAccessWrite(actor); return null; }
        return workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .filter(membership -> membership.getRole() == WorkspaceMemberRole.OWNER)
                .orElseThrow(() -> memberManageForbidden("구성원을 관리할 권한이 없습니다", notOwnerDetail));
    }

    @Transactional(readOnly = true)
    public kr.ac.pusan.pickle.access.dto.ActorAccessImpact previewMemberRole(AuthenticatedUser actor,
            UUID workspaceId, UUID userId, UpdateWorkspaceMemberRequest request) {
        kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireWorkspaceAccessWrite(actor);
        Workspace workspace = findWorkspace(workspaceId);
        if (workspace.getKind() == WorkspaceKind.PERSONAL) { throw memberManageForbidden("구성원을 관리할 권한이 없습니다", "개인 워크스페이스의 구성원은 변경할 수 없습니다."); }
        workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), memberUserId(userId)).orElseThrow(WorkspaceService::memberNotFound);
        return memberRoleImpact(actor, workspace, userId, request.role());
    }

    private kr.ac.pusan.pickle.access.dto.ActorAccessImpact memberRoleImpact(AuthenticatedUser actor,
            Workspace workspace, UUID targetId, WorkspaceMemberRole role) {
        String binding = "MEMBER_ROLE:" + workspace.getPublicId() + ':' + targetId + ':' + role + ':'
                + workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspace.getId()).stream()
                        .map(m -> m.getUserId() + ":" + m.getRole()).toList();
        return actor.publicId().equals(targetId) ? accessImpacts.membership(actor, workspace, role, binding, List.of())
                : accessImpacts.bind(binding, List.of(), List.of());
    }

    private Map<String, Object> memberAudit(UUID targetId, WorkspaceMemberRole previous, WorkspaceMemberRole next,
            boolean support, Map<String, Object> gain) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>(gain);
        detail.put("userId", targetId); detail.put("previousRole", previous.name()); detail.put("role", next.name());
        if (support) { detail.put("adminIntervention", true); }
        return detail;
    }

    void requireActiveActor(AuthenticatedUser actor) {
        if (userRepository.findById(actor.id()).map(User::getStatus).orElse(null) != kr.ac.pusan.pickle.user.UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID, "인증이 필요합니다", "현재 활성 계정으로 다시 로그인해 주세요.");
        }
    }

    private WorkspaceDetailResponse toDetail(Workspace workspace, WorkspaceMemberRole myRole) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspace.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return WorkspaceDetailResponse.from(workspace, myRole, members.stream()
                .map(member -> WorkspaceMemberResponse.from(member, users.get(member.getUserId())))
                .toList());
    }

    /** All read/manage paths exclude soft-deleted workspaces — a deleted workspace answers 404. */
    Workspace findWorkspace(UUID publicWorkspaceId) {
        return workspaceRepository.findByPublicIdAndDeletedAtIsNull(publicWorkspaceId)
                .orElseThrow(WorkspaceService::workspaceNotFound);
    }

    /**
     * The member behind a public account id. An id no account has reads as
     * "not a member of this workspace", the same 404 a real account outside
     * the workspace gets.
     */
    private long memberUserId(UUID publicUserId) {
        return userRepository.findByPublicId(publicUserId).map(User::getId)
                .orElseThrow(WorkspaceService::memberNotFound);
    }

    private static ApiException workspaceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }

    private static String normalize(String description) {
        return Texts.blankToNull(description);
    }

    private static ApiException memberNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 사용자는 이 워크스페이스의 구성원이 아닙니다.");
    }

    private static ApiException accessDenied(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", detail);
    }

    private static ApiException memberManageForbidden(String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_MEMBER_MANAGE_FORBIDDEN, title, detail);
    }

    private static ApiException soleOwnerRemoval(String title) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_SOLE_OWNER_REMOVAL, title,
                "다른 구성원을 소유자로 지정한 뒤 다시 시도해 주세요.");
    }

}
