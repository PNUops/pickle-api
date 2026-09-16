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

    /**
     * The requests this person filed.
     *
     * <p>What an unscoped list shows: nobody named a workspace, so the question
     * is "what did I ask for". A fellow member's requests are their workspace's
     * to show and come back when that workspace is named — this is the same line
     * the resource lists draw, and a request is where a resource comes from.
     */
    public static Specification<Request> filedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("requesterId"), userId);
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
