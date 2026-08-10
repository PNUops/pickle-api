package kr.ac.pusan.pickle.request;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;

/**
 * The parts of the request flow that only one kind of resource can answer.
 *
 * <p>Everything else -- who may ask, the state machine, cancellation, the
 * audit and notification wiring, the approval queue and its org scoping -- is
 * written once against {@link Request} and does not know what is being asked
 * for. A new resource type implements this and appears in both flows; it does
 * not add a second copy of them.
 */
public interface RequestTypeHandler {

    /** The type this handler answers for. */
    ResourceType type();

    /**
     * Validates the type-specific part of a submission, appending to
     * {@code errors} rather than throwing so the caller reports every problem
     * in one response.
     */
    void validateCreate(CreateRequestRequest form, List<FieldValidationError> errors);

    /** Writes the detail row for a request whose common part is already saved. */
    void saveDetail(Request request, CreateRequestRequest form);

    /** What the submission audit should record beyond the common fields. */
    Map<String, Object> submitAuditArgs(Request request);

    /** Validates the type-specific part of an approval decision. */
    void validateApprove(Request request, ApproveRequestRequest form, List<FieldValidationError> errors);

    /**
     * Creates the resource the approved request asked for and records what was
     * granted. Runs inside the approval transaction; anything that must not
     * (a durable job enqueue, for one) goes in the returned after-commit hook.
     */
    Materialized materialize(Request request, ApproveRequestRequest form, AuthenticatedUser actor);

    /**
     * The approved resource, as the common approval flow needs to see it.
     *
     * @param resourceId the new resource's id, for its first access grant
     * @param resourceName the name to show in the approval notice
     * @param auditArgs what the approval audit should record beyond the common fields
     * @param afterCommit work that must wait for the transaction to commit
     */
    record Materialized(long resourceId, String resourceName, Map<String, Object> auditArgs,
            Runnable afterCommit) {
    }
}
