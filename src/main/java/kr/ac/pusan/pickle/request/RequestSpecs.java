package kr.ac.pusan.pickle.request;

import java.util.Collection;
import java.time.LocalDate;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitation;
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

    /** Public request/applicant/workspace text only; hidden profile identifiers never enter search. */
    public static Specification<Request> text(String value) {
        String escaped = value.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return (root, query, cb) -> {
            var applicantQuery = query.subquery(Long.class);
            var applicant = applicantQuery.from(User.class);
            applicantQuery.select(applicant.get("id")).where(cb.equal(applicant.get("id"), root.get("requesterId")),
                    cb.or(cb.like(cb.lower(applicant.get("name")), pattern, '\\'), cb.like(cb.lower(applicant.get("email")), pattern, '\\')));
            var workspaceQuery = query.subquery(Long.class);
            var workspace = workspaceQuery.from(Workspace.class);
            workspaceQuery.select(workspace.get("id")).where(cb.equal(workspace.get("id"), root.get("workspaceId")),
                    cb.like(cb.lower(workspace.get("name")), pattern, '\\'));
            return cb.or(cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("purpose")), pattern, '\\'), cb.exists(applicantQuery), cb.exists(workspaceQuery));
        };
    }

    public static Specification<Request> requestedEndFrom(LocalDate value) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("reqEndDate"), value);
    }

    public static Specification<Request> requestedEndTo(LocalDate value) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("reqEndDate"), value);
    }

    public static Specification<Request> indefinite(boolean value) {
        return (root, query, cb) -> value ? cb.isNull(root.get("reqEndDate")) : cb.isNotNull(root.get("reqEndDate"));
    }

    /** Actual requester/recipient or accepted-invitation identity, without guessing pending identities. */
    public static Specification<Request> relatedUser(Long userId) {
        return (root, query, cb) -> {
            var recipients = query.subquery(Long.class);
            var recipient = recipients.from(RequestRecipient.class);
            var acceptedInvitations = recipients.subquery(Long.class);
            var invitation = acceptedInvitations.from(WorkspaceInvitation.class);
            acceptedInvitations.select(invitation.get("id")).where(cb.equal(invitation.get("id"), recipient.get("invitationId")),
                    cb.equal(invitation.get("acceptedUserId"), userId));
            recipients.select(recipient.get("id")).where(cb.equal(recipient.get("requestId"), root.get("id")),
                    cb.or(cb.equal(recipient.get("userId"), userId), cb.exists(acceptedInvitations)));
            return cb.or(cb.equal(root.get("requesterId"), userId), cb.exists(recipients));
        };
    }
}
