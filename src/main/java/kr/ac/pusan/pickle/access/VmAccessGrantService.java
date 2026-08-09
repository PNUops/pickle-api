package kr.ac.pusan.pickle.access;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.dto.AddVmAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.UpdateVmAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.VmAccessGrantView;
import kr.ac.pusan.pickle.access.dto.VmAccessListResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and editing one VM's access list (contract tag {@code vm-access}).
 *
 * <p>Who may edit it: the VM's own owners, and the owners of the workspace that owns
 * it. The second is the recovery path — a VM can end up with no owner of its
 * own, for instance when the person who requested it leaves the workspace.
 *
 * <p>A workspace owner holds no way inside a VM until they appear on its list. They
 * may put themselves there, and that is the intended escape hatch, but any edit
 * of theirs that creates content access for themselves is additionally recorded
 * as a break-glass event. Adding a workspace-wide grant is included: without that,
 * the same result could be had without the marker.
 */
@Service
public class VmAccessGrantService {

    private final VmAccessService vmAccessService;
    private final ResourceAccessGrantRepository grantRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final VmSettingsService vmSettingsService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public VmAccessGrantService(VmAccessService vmAccessService,
            ResourceAccessGrantRepository grantRepository,
            WorkspaceMemberRepository workspaceMemberRepository, WorkspaceRepository workspaceRepository,
            VmSettingsService vmSettingsService, UserRepository userRepository,
            AuditService auditService) {
        this.vmAccessService = vmAccessService;
        this.grantRepository = grantRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.vmSettingsService = vmSettingsService;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public VmAccessListResponse list(AuthenticatedUser actor, long vmId) {
        Vm vm = requireManager(actor, vmId).vm();
        String workspaceName = workspaceRepository.findById(vm.getWorkspaceId())
                .map(Workspace::getName).orElse("");
        return VmAccessListResponse.of(vm, workspaceName,
                vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME), views(vmId));
    }

    @Transactional
    public VmAccessGrantView add(AuthenticatedUser actor, long vmId,
            AddVmAccessGrantRequest request, String ip) {
        VmAccess before = requireManager(actor, vmId);
        Vm vm = before.vm();
        ResourceAccessGrant grant = request.granteeType() == AccessGranteeType.WORKSPACE
                ? ResourceAccessGrant.forOwningWorkspace(ResourceType.VM, vmId,
                        requireWorkspaceWideRole(request.role()))
                : ResourceAccessGrant.forUser(ResourceType.VM, vmId,
                        requireEligibleMember(vm, request.userId()), request.role());
        ResourceAccessGrant saved;
        try {
            saved = grantRepository.saveAndFlush(grant);
        } catch (DataIntegrityViolationException alreadyListed) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_ACCESS_GRANT_EXISTS,
                    "이미 접근 권한이 있습니다",
                    "이 대상은 이미 이 VM의 접근 목록에 있습니다. 등급을 바꾸려면 기존 항목을 수정해 주세요.");
        }
        audit(actor, before, AuditService.VM_ACCESS_GRANT_ADD, saved, null, ip);
        return view(saved);
    }

    @Transactional
    public VmAccessGrantView update(AuthenticatedUser actor, long vmId, long grantId,
            UpdateVmAccessGrantRequest request, String ip) {
        VmAccess before = requireManager(actor, vmId);
        ResourceAccessGrant grant = requireGrant(vmId, grantId);
        ResourceRole previous = grant.getRole();
        grant.setRole(grant.getGranteeType() == AccessGranteeType.WORKSPACE
                ? requireWorkspaceWideRole(request.role())
                : request.role());
        audit(actor, before, AuditService.VM_ACCESS_GRANT_UPDATE, grant, previous, ip);
        return view(grant);
    }

    @Transactional
    public void remove(AuthenticatedUser actor, long vmId, long grantId, String ip) {
        VmAccess before = requireManager(actor, vmId);
        ResourceAccessGrant grant = requireGrant(vmId, grantId);
        grantRepository.delete(grant);
        audit(actor, before, AuditService.VM_ACCESS_GRANT_REMOVE, grant, null, ip);
    }

    /** Managing the list is a resource owner's right, or a workspace owner's standing one. */
    private VmAccess requireManager(AuthenticatedUser actor, long vmId) {
        VmAccess access = vmAccessService.of(actor, vmId);
        if (!access.manages()) {
            access.requireVisible();
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    "접근 권한을 관리할 권한이 없습니다",
                    "이 VM의 소유자 또는 워크스페이스 소유자만 접근 권한을 관리할 수 있습니다.");
        }
        return access;
    }

    /**
     * A named grant may only be given to a member of the owning workspace — the rule
     * that keeps the list from disagreeing with the 404 that hides this VM from
     * everyone else.
     */
    private long requireEligibleMember(Vm vm, Long userId) {
        if (userId == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("userId",
                    "대상 사용자를 지정해 주세요.")));
        }
        boolean member = workspaceMemberRepository.findByWorkspaceIdAndUserId(vm.getWorkspaceId(), userId)
                .isPresent();
        boolean active = userRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE).isPresent();
        if (!member || !active) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("userId",
                    "이 VM을 소유한 워크스페이스의 구성원만 접근 권한을 받을 수 있습니다. 먼저 워크스페이스에 추가해 주세요.")));
        }
        return userId;
    }

    /** The whole workspace is never handed the rungs that manage access or destroy. */
    private ResourceRole requireWorkspaceWideRole(ResourceRole role) {
        if (role == ResourceRole.OWNER || role == ResourceRole.EDITOR) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "워크스페이스 전체에는 참여자 또는 열람자까지만 부여할 수 있습니다. 그보다 높은 등급은 "
                            + "구성원을 지정해 부여해 주세요.")));
        }
        return role;
    }

    private ResourceAccessGrant requireGrant(long vmId, long grantId) {
        return grantRepository.findById(grantId)
                .filter(grant -> grant.getResourceType() == ResourceType.VM
                        && grant.getResourceId() == vmId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "해당 접근 권한이 존재하지 않습니다."));
    }

    private void audit(AuthenticatedUser actor, VmAccess before, String action,
            ResourceAccessGrant grant, ResourceRole previousRole, String ip) {
        Vm vm = before.vm();
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("grantId", grant.getId());
        detail.put("granteeType", grant.getGranteeType().name());
        if (grant.getUserId() != null) {
            detail.put("granteeUserId", grant.getUserId());
        }
        detail.put("role", grant.getRole().name());
        if (previousRole != null) {
            detail.put("previousRole", previousRole.name());
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), action, "vm", vm.getId(),
                Map.copyOf(detail), ip);
        if (opensTheDoorForActor(actor, before, action, grant)) {
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.VM_ACCESS_BREAK_GLASS, "vm", vm.getId(), Map.copyOf(detail), ip);
        }
    }

    /**
     * True when this edit is what lets the actor inside a VM they could not
     * reach before it — the case the break-glass record exists for.
     *
     * <p>Both sides of the comparison are needed. Looking only at the state
     * after the write marks a resource owner who was already inside, and marks
     * someone <em>lowering</em> their own rung, which would put a false
     * emergency-access record in the trail; a signal that fires on ordinary
     * edits is worth nothing to whoever reads the audit later.
     */
    private boolean opensTheDoorForActor(AuthenticatedUser actor, VmAccess before, String action,
            ResourceAccessGrant grant) {
        if (AuditService.VM_ACCESS_GRANT_REMOVE.equals(action)) {
            return false;
        }
        boolean namesTheActor = grant.getGranteeType() == AccessGranteeType.WORKSPACE
                || Long.valueOf(actor.id()).equals(grant.getUserId());
        if (!namesTheActor || before.atLeast(ResourceRole.MEMBER)) {
            return false;
        }
        return vmAccessService.of(before.vm(), actor.id()).atLeast(ResourceRole.MEMBER);
    }

    private List<VmAccessGrantView> views(long vmId) {
        List<ResourceAccessGrant> grants = grantRepository
                .findByResourceTypeAndResourceIdOrderByIdAsc(ResourceType.VM, vmId);
        Map<Long, User> users = userRepository.findAllById(grants.stream()
                        .map(ResourceAccessGrant::getUserId).filter(java.util.Objects::nonNull)
                        .toList()).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        return grants.stream()
                .map(grant -> VmAccessGrantView.of(grant,
                        grant.getUserId() == null ? null : users.get(grant.getUserId())))
                .toList();
    }

    private VmAccessGrantView view(ResourceAccessGrant grant) {
        return VmAccessGrantView.of(grant,
                grant.getUserId() == null ? null : userRepository.findById(grant.getUserId())
                        .orElse(null));
    }
}
