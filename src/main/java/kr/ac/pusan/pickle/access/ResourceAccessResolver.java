package kr.ac.pusan.pickle.access;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
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
    private final UserRepository userRepository;

    public ResourceAccessResolver(WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
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
     * Which of these resources the requester may see in full, and who to ask
     * about the rest — one list view's worth of masking decisions.
     *
     * @param reachable  the resources some grant opens for them
     * @param ownerNames per resource, the names of its owners: who a member
     *                   without a grant asks for one
     */
    public record ListAccess(Set<Long> reachable, Map<Long, List<String>> ownerNames) {
    }

    /**
     * The batch form of {@link #standing} that list views run on: one grant
     * query for a whole page instead of one per row, reduced to the only two
     * answers a list needs. It lives here for the same reason {@code standing}
     * does — reachability is the access rule, and a second copy of it per
     * resource type would be a second policy within a release or two.
     *
     * <p>Membership of the owning workspace is deliberately not re-checked per
     * row: every caller builds its page from the requester's own workspace
     * memberships, so each row already is a resource of a workspace they are in.
     */
    @Transactional(readOnly = true)
    public ListAccess listAccess(ResourceType type, List<Long> resourceIds, long userId) {
        if (resourceIds.isEmpty()) {
            return new ListAccess(Set.of(), Map.of());
        }
        List<ResourceAccessGrant> grants =
                grantRepository.findByResourceTypeAndResourceIdIn(type, resourceIds);
        Set<Long> reachable = new HashSet<>();
        Map<Long, List<Long>> ownerIds = new LinkedHashMap<>();
        for (ResourceAccessGrant grant : grants) {
            if (grant.getGranteeType() == AccessGranteeType.WORKSPACE
                    || Long.valueOf(userId).equals(grant.getUserId())) {
                reachable.add(grant.getResourceId());
            }
            if (grant.getRole() == ResourceRole.OWNER && grant.getUserId() != null) {
                ownerIds.computeIfAbsent(grant.getResourceId(), key -> new ArrayList<>())
                        .add(grant.getUserId());
            }
        }
        Map<Long, String> names = userRepository.findAllById(ownerIds.values().stream()
                        .flatMap(List::stream).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        Map<Long, List<String>> ownerNames = new LinkedHashMap<>();
        ownerIds.forEach((resourceId, ids) -> ownerNames.put(resourceId, ids.stream()
                .map(names::get).filter(Objects::nonNull).toList()));
        return new ListAccess(reachable, ownerNames);
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
