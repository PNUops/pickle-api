package kr.ac.pusan.pickle.access;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.http.HttpStatus;

/**
 * The words one resource type uses when it refuses.
 *
 * <p>Which refusal a situation calls for, and what status and code it carries,
 * is a rule and lives in the shared code; only the sentence a person reads
 * differs per type, because a sentence about a VM and one about an API key are
 * not the same sentence and a noun cannot be substituted into Korean and stay
 * grammatical. Each type builds one of these once, and it is the only home of
 * those sentences — the resource's own services read them from here too.
 *
 * @param notFoundDetail          the masking 404: an existing but unreachable
 *                                resource has to read as a missing one
 * @param whenNoGrant             403 for a member of the owning workspace who
 *                                holds no grant and can already see it listed
 * @param whenNotGrantManager     403 for someone who may see the resource but
 *                                not decide who else reaches it
 * @param granteeIneligibleDetail field error when a grant names someone the
 *                                owning workspace does not have
 * @param grantExistsCode         error code for a second entry naming a target
 *                                the list already carries
 * @param whenGrantExists         what that conflict says
 */
public record ResourceAccessMessages(
        String notFoundDetail,
        Refusal whenNoGrant,
        Refusal whenNotGrantManager,
        String granteeIneligibleDetail,
        String grantExistsCode,
        Refusal whenGrantExists) {

    /** A title and the sentence under it, the shape every error body here takes. */
    public record Refusal(String title, String detail) {
    }

    /** Existence masked: the same answer as an id that names nothing. */
    public ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", notFoundDetail);
    }

    /** Visible but closed — an honest 403 rather than a lie about existence. */
    public ApiException noGrant() {
        return forbidden(whenNoGrant);
    }

    /** Visible, and not theirs to hand out. */
    public ApiException notGrantManager() {
        return forbidden(whenNotGrantManager);
    }

    /** A named grant may only reach a member of the owning workspace. */
    public ApiException granteeIneligible() {
        return ApiException.validationFailed(List.of(
                new FieldValidationError("userId", granteeIneligibleDetail)));
    }

    /** One entry per target: a second one is a conflict, not a second opinion. */
    public ApiException alreadyListed() {
        return new ApiException(HttpStatus.CONFLICT, grantExistsCode, whenGrantExists.title(),
                whenGrantExists.detail());
    }

    private static ApiException forbidden(Refusal refusal) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                refusal.title(), refusal.detail());
    }
}
