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

    /**
     * Whether a submission of this kind is approved without a reviewer.
     *
     * <p>Asked once, at submission, and the answer is the type's to give
     * because the thing it depends on is the type's own — for a name it is the
     * root the applicant picked. Default false: a kind that says nothing keeps
     * the behaviour every kind had before this existed, which is that a person
     * decides.</p>
     */
    default boolean isAutoApproved(CreateRequestRequest form) {
        return false;
    }

    /**
     * Whether this kind's resource governs its own lifetime.
     *
     * <p>True means the common period is neither asked for nor stored: the form
     * does not show the control, the submission is not refused for leaving it
     * empty, and the request carries no end date. A name is the case — its life
     * is the renewal deadline, and a granted period beside it would be a second
     * clock that disagrees with the first.</p>
     *
     * <p>This is the answer to a question the request flow asks of every kind,
     * not a licence to ignore what was sent. A period that arrived anyway is
     * dropped rather than stored, because storing it would show the applicant a
     * date on their request that nothing honours.</p>
     */
    default boolean ownsItsOwnLifetime() {
        return false;
    }

    /**
     * Whether {@link #owningOrgId} answers for this kind, asked without looking
     * at the form.
     *
     * <p>Separate from {@code owningOrgId} because the two are needed at
     * different moments. Whether the applicant had to send an organisation is
     * known before anything is validated, and so the refusal for leaving it out
     * can join the same 422 as every other missing field; working out
     * <em>which</em> organisation can only happen after the kind's own fields
     * are known to be good, because for a name it means reading the root that
     * was asked for.</p>
     */
    default boolean derivesOrgId() {
        return false;
    }

    /**
     * The organisation this request belongs to, when the type knows better than
     * the applicant did.
     *
     * <p>Empty for every kind whose organisation is simply the one that was
     * picked. A name is the exception: its institution is a property of where
     * it lives, so the root decides and the form's own {@code orgId} is
     * overwritten rather than trusted. Returning it here rather than mutating
     * the form keeps the override in one place and visible.</p>
     */
    default java.util.Optional<Long> owningOrgId(CreateRequestRequest form) {
        return java.util.Optional.empty();
    }

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
            Runnable afterCommit, Map<String, Object> notificationArgs) {
        public Materialized(long resourceId, String resourceName, Map<String, Object> auditArgs, Runnable afterCommit) {
            this(resourceId, resourceName, auditArgs, afterCommit, Map.of());
        }
    }
}
