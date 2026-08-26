package kr.ac.pusan.pickle.request;

import java.util.Collection;
import kr.ac.pusan.pickle.access.ResourceType;
import org.springframework.data.jpa.domain.Specification;

/**
 * The request-list filters, as composable pieces.
 *
 * <p>Written this way because the axes multiply: status, resource type, and
 * either a workspace (user side) or an organisation (admin side). As derived
 * queries that is one method per combination, and adding the type axis would
 * have doubled the count.
 */
public final class RequestSpecs {

    private RequestSpecs() {
    }

    /** Own requests plus those of workspaces the caller belongs to. */
    public static Specification<Request> visibleTo(Long userId, Collection<Long> workspaceIds) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("requesterId"), userId),
                root.get("workspaceId").in(workspaceIds));
    }

    public static Specification<Request> status(RequestStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Request> type(ResourceType type) {
        return (root, query, cb) -> cb.equal(root.get("resourceType"), type);
    }

    public static Specification<Request> workspace(Long workspaceId) {
        return (root, query, cb) -> cb.equal(root.get("workspaceId"), workspaceId);
    }

    /** Restricts to a set of orgs: an admin may manage more than one (V90). */
    public static Specification<Request> orgIn(Collection<Long> orgIds) {
        return (root, query, cb) -> root.get("orgId").in(orgIds);
    }
}
