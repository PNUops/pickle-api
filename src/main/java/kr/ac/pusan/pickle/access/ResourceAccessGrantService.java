package kr.ac.pusan.pickle.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessGrantView;
import kr.ac.pusan.pickle.access.dto.ResourceAccessListResponse;
import kr.ac.pusan.pickle.access.dto.UpdateResourceAccessGrantRequest;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and editing one resource's access list, for every resource type.
 *
 * <p>Who may edit it: the resource's own owners, and the owners of the
 * workspace that owns it. The second is the recovery path — a resource can end
 * up with no owner of its own, for instance when the person who requested it
 * leaves the workspace.
 *
 * <p>A workspace owner holds no way inside a resource until they appear on its
 * list. They may put themselves there, and that is the intended escape hatch,
 * but any edit of theirs that creates content access for themselves is
 * additionally recorded as a break-glass event. Adding a workspace-wide grant
 * is included: without that, the same result could be had without the marker.
 *
 * <p>Nothing here knows what a VM is. What a resource type contributes is its
 * {@link ResourceTypeAdapter}: how to load the thing, which workspace owns it,
 * what its limited view shows, and the sentences it refuses in. Everything else
 * — the rungs, the cap on workspace-wide grants, the eligibility of a grantee,
 * the break-glass boundary — is one implementation, because two copies of "who
 * may reach a resource" would be two policies within a release or two.
 */
@Service
public class ResourceAccessGrantService {

    private final Map<ResourceType, ResourceTypeAdapter> adapters;
    private final ResourceAccessResolver resolver;
    private final ResourceAccessGrantRepository grantRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;

    public ResourceAccessGrantService(List<ResourceTypeAdapter> adapters,
            ResourceAccessResolver resolver, ResourceAccessGrantRepository grantRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository, UserRepository userRepository,
            AuditService auditService, AuditIds auditIds) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(ResourceTypeAdapter::type, Function.identity()));
        this.resolver = resolver;
        this.grantRepository = grantRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
    }

    @Transactional(readOnly = true)
    public ResourceAccessListResponse list(AuthenticatedUser actor, ResourceType type,
            UUID resourceId) {
        Managed managed = requireManager(actor, type, resourceId);
        Workspace workspace = workspaceRepository.findById(managed.resource().workspaceId())
                .orElse(null);
        return ResourceAccessListResponse.of(type, managed.resource(),
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(),
                views(type, managed.resource().id()));
    }

    @Transactional
    public ResourceAccessGrantView add(AuthenticatedUser actor, ResourceType type, UUID resourceId,
            AddResourceAccessGrantRequest request, String ip) {
        Managed managed = requireManager(actor, type, resourceId);
        long id = managed.resource().id();
        ResourceAccessGrant grant = request.granteeType() == AccessGranteeType.WORKSPACE
                ? ResourceAccessGrant.forOwningWorkspace(type, id,
                        requireWorkspaceWideRole(request.role()))
                : ResourceAccessGrant.forUser(type, id,
                        requireEligibleMember(managed, request.userId()), request.role());
        ResourceAccessGrant saved;
        try {
            saved = grantRepository.saveAndFlush(grant);
        } catch (DataIntegrityViolationException alreadyListed) {
            throw managed.messages().alreadyListed();
        }
        audit(actor, managed, GrantChange.ADD, saved, null, ip);
        return view(saved);
    }

    @Transactional
    public ResourceAccessGrantView update(AuthenticatedUser actor, ResourceType type,
            UUID resourceId, UUID grantId, UpdateResourceAccessGrantRequest request, String ip) {
        Managed managed = requireManager(actor, type, resourceId);
        ResourceAccessGrant grant = requireGrant(managed, grantId);
        ResourceRole previous = grant.getRole();
        grant.setRole(grant.getGranteeType() == AccessGranteeType.WORKSPACE
                ? requireWorkspaceWideRole(request.role())
                : request.role());
        audit(actor, managed, GrantChange.UPDATE, grant, previous, ip);
        return view(grant);
    }

    @Transactional
    public void remove(AuthenticatedUser actor, ResourceType type, UUID resourceId, UUID grantId,
            String ip) {
        Managed managed = requireManager(actor, type, resourceId);
        ResourceAccessGrant grant = requireGrant(managed, grantId);
        grantRepository.delete(grant);
        audit(actor, managed, GrantChange.REMOVE, grant, null, ip);
    }

    /** Managing the list is a resource owner's right, or a workspace owner's standing one. */
    private Managed requireManager(AuthenticatedUser actor, ResourceType type, UUID resourceId) {
        ResourceTypeAdapter adapter = adapterFor(type);
        ResourceIdentity resource = adapter.identifyByPublicId(resourceId)
                .orElseThrow(() -> adapter.accessMessages().notFound());
        ResourceStanding standing = resolver.standing(type, resource.id(), resource.workspaceId(),
                actor.id());
        if (!standing.manages()) {
            // An outsider is not told the resource exists and a member without
            // a grant is refused in the open; only somebody who can already see
            // it is told that managing the list is a rung above theirs.
            standing.requireVisible(adapter.accessMessages());
            throw adapter.accessMessages().notGrantManager();
        }
        return new Managed(adapter, resource, standing);
    }

    private ResourceTypeAdapter adapterFor(ResourceType type) {
        ResourceTypeAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for resource type " + type);
        }
        return adapter;
    }

    /**
     * A named grant may only be given to a member of the owning workspace — the
     * rule that keeps the list from disagreeing with the 404 that hides the
     * resource from everyone else.
     */
    private long requireEligibleMember(Managed managed, UUID publicUserId) {
        if (publicUserId == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("userId",
                    "대상 사용자를 지정해 주세요.")));
        }
        // An id no account has is refused as ineligible, not as missing: the
        // grantee's existence is not this endpoint's to disclose.
        Long userId = userRepository.findByPublicId(publicUserId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(kr.ac.pusan.pickle.user.User::getId)
                .orElseThrow(() -> managed.messages().granteeIneligible());
        boolean member = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(managed.resource().workspaceId(), userId).isPresent();
        if (!member) {
            throw managed.messages().granteeIneligible();
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

    /** A grant id means nothing outside the resource it was issued under. */
    private ResourceAccessGrant requireGrant(Managed managed, UUID grantId) {
        return grantRepository.findByPublicId(grantId)
                .filter(grant -> grant.getResourceType() == managed.adapter().type()
                        && grant.getResourceId() == managed.resource().id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "해당 접근 권한이 존재하지 않습니다."));
    }

    private void audit(AuthenticatedUser actor, Managed managed, GrantChange change,
            ResourceAccessGrant grant, ResourceRole previousRole, String ip) {
        ResourceAccessAudit names = managed.adapter().accessAudit();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grantId", grant.getPublicId());
        detail.put("granteeType", grant.getGranteeType().name());
        if (grant.getUserId() != null) {
            detail.put("granteeUserId", auditIds.user(grant.getUserId()));
        }
        detail.put("role", grant.getRole().name());
        if (previousRole != null) {
            detail.put("previousRole", previousRole.name());
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), names.actionOf(change),
                names.targetType(), managed.resource().publicId(), Map.copyOf(detail), ip);
        if (opensTheDoorForActor(actor, managed, change, grant)) {
            auditService.recordAfterCommit(actor.id(), actor.role().name(), names.breakGlass(),
                    names.targetType(), managed.resource().publicId(), Map.copyOf(detail), ip);
        }
    }

    /**
     * True when this edit is what lets the actor inside a resource they could
     * not reach before it — the case the break-glass record exists for.
     *
     * <p>Both sides of the comparison are needed. Looking only at the state
     * after the write marks a resource owner who was already inside, and marks
     * someone <em>lowering</em> their own rung, which would put a false
     * emergency-access record in the trail; a signal that fires on ordinary
     * edits is worth nothing to whoever reads the audit later.
     */
    private boolean opensTheDoorForActor(AuthenticatedUser actor, Managed managed,
            GrantChange change, ResourceAccessGrant grant) {
        if (change == GrantChange.REMOVE) {
            return false;
        }
        boolean namesTheActor = grant.getGranteeType() == AccessGranteeType.WORKSPACE
                || Long.valueOf(actor.id()).equals(grant.getUserId());
        if (!namesTheActor || managed.standing().atLeast(ResourceRole.MEMBER)) {
            return false;
        }
        return resolver.standing(managed.adapter().type(), managed.resource().id(),
                managed.resource().workspaceId(), actor.id()).atLeast(ResourceRole.MEMBER);
    }

    private List<ResourceAccessGrantView> views(ResourceType type, long resourceId) {
        List<ResourceAccessGrant> grants = grantRepository
                .findByResourceTypeAndResourceIdOrderByIdAsc(type, resourceId);
        Map<Long, User> users = userRepository.findAllById(grants.stream()
                        .map(ResourceAccessGrant::getUserId).filter(Objects::nonNull)
                        .toList()).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        return grants.stream()
                .map(grant -> ResourceAccessGrantView.of(grant,
                        grant.getUserId() == null ? null : users.get(grant.getUserId())))
                .toList();
    }

    private ResourceAccessGrantView view(ResourceAccessGrant grant) {
        return ResourceAccessGrantView.of(grant,
                grant.getUserId() == null ? null : userRepository.findById(grant.getUserId())
                        .orElse(null));
    }

    /**
     * One resource, loaded, plus the standing the actor held on it before the
     * edit — which is half of what the break-glass comparison needs.
     */
    private record Managed(ResourceTypeAdapter adapter, ResourceIdentity resource,
            ResourceStanding standing) {

        ResourceAccessMessages messages() {
            return adapter.accessMessages();
        }
    }
}
