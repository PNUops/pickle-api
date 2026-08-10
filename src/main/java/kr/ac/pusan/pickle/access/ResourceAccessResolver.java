package kr.ac.pusan.pickle.access;

import java.util.List;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that turns the access list into a standing, for every resource
 * type.
 *
 * <p>A type's own service says which resource is meant and which workspace owns
 * it; what that buys the requester is decided here, so that a second resource
 * type cannot arrive with a second opinion on what a grant means.
 *
 * <p>Admin surfaces are deliberately not routed through here: their scope is
 * the organisation, not the access list, and mixing the two is how a bypass
 * gets written by accident.
 */
@Service
public class ResourceAccessResolver {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;

    public ResourceAccessResolver(WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
    }

    /** Standing of one user on one resource of the workspace that owns it. */
    @Transactional(readOnly = true)
    public ResourceStanding standing(ResourceType type, long resourceId, long owningWorkspaceId,
            long userId) {
        WorkspaceMemberRole membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(owningWorkspaceId, userId)
                .map(WorkspaceMember::getRole)
                .orElse(null);
        return new ResourceStanding(grantedRole(type, resourceId, userId, membership != null),
                membership != null, membership == WorkspaceMemberRole.OWNER);
    }

    /**
     * The strongest rung the access list gives this person: their own grant and
     * the workspace-wide one, whichever is higher.
     *
     * <p>A grant counts only while its holder is still in the owning workspace.
     * Losing membership already deletes their grants, so this changes nothing
     * in practice — it is here so that a missed cleanup cannot leave someone
     * reaching a resource of a workspace they left.
     */
    private ResourceRole grantedRole(ResourceType type, long resourceId, long userId,
            boolean owningWorkspaceMember) {
        if (!owningWorkspaceMember) {
            return null;
        }
        ResourceRole best = null;
        List<ResourceAccessGrant> grants = grantRepository
                .findByResourceTypeAndResourceIdOrderByIdAsc(type, resourceId);
        for (ResourceAccessGrant grant : grants) {
            boolean applies = grant.getGranteeType() == AccessGranteeType.WORKSPACE
                    || Long.valueOf(userId).equals(grant.getUserId());
            if (applies && (best == null || grant.getRole().atLeast(best))) {
                best = grant.getRole();
            }
        }
        return best;
    }
}
