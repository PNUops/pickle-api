package kr.ac.pusan.pickle.workspace;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.workspace.dto.AddWorkspaceMemberRequest;
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
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.request.RequestStatus;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEAM/PROJECT workspace management (contract tag {@code workspaces}). Authorization
 * is resolved in this layer from a single membership row per request:
 * OWNER edits workspace info, manages members and transfers ownership;
 * PERSONAL workspaces have immutable membership.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final VmRepository vmRepository;
    private final RequestRepository requestRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final List<ResourceTypeAdapter> resourceAdapters;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository, VmRepository vmRepository,
            RequestRepository requestRepository, AuditService auditService,
            NotificationService notificationService, List<ResourceTypeAdapter> resourceAdapters) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.vmRepository = vmRepository;
        this.requestRepository = requestRepository;
        this.auditService = auditService;
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
        if (request.kind() == WorkspaceKind.PERSONAL) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("kind",
                    "PERSONAL 워크스페이스는 자동 생성됩니다. TEAM 또는 PROJECT만 생성할 수 있습니다.")));
        }
        Workspace workspace = workspaceRepository.save(new Workspace(request.kind(),
                request.name().strip(), normalize(request.description())));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, actor.id(), WorkspaceMemberRole.OWNER));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_CREATE,
                "workspace", workspace.getId(),
                Map.of("kind", workspace.getKind().name(), "name", workspace.getName()), ip);
        return toDetail(workspace, WorkspaceMemberRole.OWNER);
    }

    @Transactional(readOnly = true)
    public WorkspaceDetailResponse get(AuthenticatedUser actor, long workspaceId) {
        Workspace workspace = findWorkspace(workspaceId);
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id())
                .orElseThrow(() -> accessDenied("워크스페이스 구성원만 조회할 수 있습니다."));
        return toDetail(workspace, membership.getRole());
    }

    @Transactional
    public WorkspaceDetailResponse update(AuthenticatedUser actor, long workspaceId, UpdateWorkspaceRequest request) {
        Workspace workspace = findWorkspace(workspaceId);
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id())
                .orElseThrow(() -> accessDenied("워크스페이스 소유자(OWNER)만 수정할 수 있습니다."));
        if (membership.getRole() != WorkspaceMemberRole.OWNER) {
            throw accessDenied("워크스페이스 소유자(OWNER)만 수정할 수 있습니다.");
        }
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
        return toDetail(workspace, membership.getRole());
    }

    @Transactional
    public WorkspaceMemberResponse addMember(AuthenticatedUser actor, long workspaceId,
            AddWorkspaceMemberRequest request, String ip) {
        Workspace workspace = findWorkspace(workspaceId);
        requireOwnerForMemberManagement(workspace, actor, "워크스페이스 소유자(OWNER)만 구성원을 추가할 수 있습니다.",
                "PERSONAL 워크스페이스에는 구성원을 추가할 수 없습니다.");
        if (request.role() == WorkspaceMemberRole.OWNER) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "OWNER 역할은 구성원 추가로 부여할 수 없습니다. 구성원으로 추가한 뒤 역할 변경으로 "
                            + "소유자를 지정해 주세요.")));
        }

        User target = userRepository.findByEmail(Texts.normalizeEmail(request.email()))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.WORKSPACE_MEMBER_USER_NOT_FOUND, "사용자를 찾을 수 없습니다",
                        "해당 이메일로 가입된 사용자가 없습니다. 가입 후 다시 시도해 주세요."));
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, target.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_MEMBER_ALREADY_EXISTS,
                    "이미 워크스페이스 구성원입니다", "해당 사용자는 이미 이 워크스페이스의 구성원입니다.");
        }

        WorkspaceMember member;
        try {
            member = workspaceMemberRepository.save(new WorkspaceMember(workspace, target.getId(), request.role()));
        } catch (DataIntegrityViolationException raceWithConcurrentAdd) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_MEMBER_ALREADY_EXISTS,
                    "이미 워크스페이스 구성원입니다", "해당 사용자는 이미 이 워크스페이스의 구성원입니다.");
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_ADD,
                "workspace", workspaceId,
                Map.of("userId", target.getId(), "email", target.getEmail(), "role", member.getRole().name()), ip);
        return WorkspaceMemberResponse.from(member, target);
    }

    @Transactional
    public WorkspaceMemberResponse updateMemberRole(AuthenticatedUser actor, long workspaceId, long targetUserId,
            UpdateWorkspaceMemberRequest request, String ip) {
        Workspace workspace = findWorkspace(workspaceId);
        requireOwnerForMemberManagement(workspace, actor,
                "워크스페이스 소유자(OWNER)만 역할을 변경할 수 있습니다.", "PERSONAL 워크스페이스의 구성원은 변경할 수 없습니다.");
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
        target.setRole(request.role());

        // The audit is deferred to after commit (recordAfterCommit), so a
        // failure anywhere in this method leaves no audit row for a change that
        // never committed.
        User targetUser = userRepository.findById(targetUserId).orElseThrow(WorkspaceService::memberNotFound);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_MEMBER_UPDATE,
                "workspace", workspaceId,
                Map.of("userId", targetUserId, "previousRole", previousRole.name(),
                        "role", target.getRole().name()), ip);
        return WorkspaceMemberResponse.from(target, targetUser);
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, long workspaceId, long targetUserId, String ip) {
        Workspace workspace = findWorkspace(workspaceId);
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", "PERSONAL 워크스페이스의 구성원은 변경할 수 없습니다.");
        }
        WorkspaceMember actorMembership = workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspaceId, actor.id())
                .orElseThrow(() -> memberManageForbidden("구성원을 제거할 권한이 없습니다",
                        "워크스페이스 소유자(OWNER)만 다른 구성원을 제거할 수 있습니다."));
        boolean selfLeave = targetUserId == actor.id();
        if (!selfLeave && actorMembership.getRole() != WorkspaceMemberRole.OWNER) {
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
                "workspace", workspaceId,
                Map.of("userId", targetUserId, "previousRole", target.getRole().name(),
                        "selfLeave", selfLeave, "revokedGrants", revokedGrants), ip);
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
     * workspaces are never deletable (409), and a workspace with any non-destroyed VM
     * (DELETED excluded, DELETING counts as blocking — shared
     * {@link VmRepository#countActiveByWorkspaceId}) is refused (409). The row is
     * kept (VM/audit history) with {@code deleted_at} stamped; ACTIVE members
     * are notified and the deletion is audited.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, long workspaceId, String ip) {
        Workspace workspace = workspaceRepository.findByIdAndDeletedAtIsNull(workspaceId)
                .orElseThrow(WorkspaceService::workspaceNotFound);
        WorkspaceMember membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actor.id())
                .orElseThrow(WorkspaceService::workspaceNotFound); // non-member: mask existence
        if (membership.getRole() != WorkspaceMemberRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    "워크스페이스를 삭제할 권한이 없습니다", "워크스페이스의 OWNER만 워크스페이스를 삭제할 수 있습니다.");
        }
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_PERSONAL_UNDELETABLE,
                    "워크스페이스를 삭제할 수 없습니다",
                    "개인 워크스페이스는 삭제할 수 없습니다. 계정 탈퇴 시에만 함께 정리됩니다.");
        }
        if (vmRepository.countActiveByWorkspaceId(workspaceId, VmStatus.DELETED) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.WORKSPACE_HAS_ACTIVE_VMS,
                    "워크스페이스를 삭제할 수 없습니다",
                    "워크스페이스에 삭제되지 않은 VM이 있습니다. VM 삭제(파기 완료) 후 다시 시도해 주세요.");
        }

        // Cancel the workspace's in-flight (SUBMITTED) VM requests in this tx so an
        // approval racing the delete can't provision into a dead workspace. Each
        // request is locked and re-checked (same guard the cancel/approve paths
        // use) — a request the approver already decided is left alone, and its
        // approval that lost the row lock hits the existing SUBMITTED check
        // (409 REQUEST_ALREADY_DECIDED) once this delete commits.
        for (Request pending : requestRepository
                .findByWorkspaceIdAndStatus(workspaceId, RequestStatus.SUBMITTED)) {
            Request locked = requestRepository.findWithLockById(pending.getId()).orElse(null);
            if (locked == null || locked.getStatus() != RequestStatus.SUBMITTED) {
                continue;
            }
            locked.setStatus(RequestStatus.CANCELED);
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.REQUEST_CANCEL, "request", locked.getId(),
                    Map.of("workspaceId", workspaceId, "reason", "workspace_deleted"), ip);
        }

        // Recipients are resolved before the soft-delete flips visibility; the
        // membership rows themselves are kept (only the workspace row is stamped).
        List<Long> recipients = notificationService.workspaceMemberIds(workspaceId);
        workspace.softDelete(actor.id(), Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.WORKSPACE_DELETE,
                "workspace", workspaceId, Map.of("kind", workspace.getKind().name(), "name", workspace.getName()), ip);
        notificationService.publish(recipients, NotificationEvent.WORKSPACE_DELETED,
                Map.of("workspaceId", workspaceId, "workspaceName", workspace.getName()), "workspace_deleted:" + workspaceId);
    }

    /**
     * Member-management gate (add/role-change): actor must be OWNER and the
     * workspace must not be PERSONAL — both violations render 403
     * {@code WORKSPACE_MEMBER_MANAGE_FORBIDDEN} per contract.
     */
    private WorkspaceMember requireOwnerForMemberManagement(Workspace workspace, AuthenticatedUser actor,
            String notOwnerDetail, String personalDetail) {
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", personalDetail);
        }
        return workspaceMemberRepository.findWithLockByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .filter(membership -> membership.getRole() == WorkspaceMemberRole.OWNER)
                .orElseThrow(() -> memberManageForbidden("구성원을 관리할 권한이 없습니다", notOwnerDetail));
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
    private Workspace findWorkspace(long workspaceId) {
        return workspaceRepository.findByIdAndDeletedAtIsNull(workspaceId)
                .orElseThrow(WorkspaceService::workspaceNotFound);
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
