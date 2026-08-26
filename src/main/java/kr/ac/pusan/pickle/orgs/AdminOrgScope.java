package kr.ac.pusan.pickle.orgs;

import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;

/**
 * How an admin surface decides which organisations one request may touch.
 *
 * <p>Six services asked this question and each had written its own answer, with
 * three different behaviours for the same input; one of them pinned silently
 * where the others answered 404. The decision lives here so the surfaces agree,
 * and so the difference that actually matters stays visible: {@link #read} is
 * the set an account may <b>see</b>, {@link #operated} the set it may
 * <b>act</b> in, and a role that only reads belongs to the first and not the
 * second.
 *
 * <p>The caller resolves the public organisation id to an internal one and
 * passes both, because the surfaces reach the {@code orgs} table by different
 * routes; {@code requested} is null when the id names no organisation.
 */
public final class AdminOrgScope {

    /**
     * The organisations an admin <b>read</b> answers for. The sys tier reads
     * every organisation and may narrow to one. The org tier reads the
     * organisations it holds any role in and may narrow within them; naming one
     * it does not hold answers 404 exactly as an unknown id does, so which
     * organisations exist stays private.
     */
    public static OrgScope read(AuthenticatedUser actor, UUID orgId, Long requested) {
        return resolve(actor, orgId, requested, actor.readableOrgIds());
    }

    /**
     * The organisations an admin surface that is <b>not</b> a plain read answers
     * for: the account must be able to act there, so a read-only role is
     * excluded. The audit log uses this rather than {@link #read} — it carries
     * login addresses, which are evidence rather than operational state.
     */
    public static OrgScope operated(AuthenticatedUser actor, UUID orgId, Long requested) {
        return resolve(actor, orgId, requested, actor.operatedOrgIds());
    }

    private static OrgScope resolve(AuthenticatedUser actor, UUID orgId, Long requested,
            Set<Long> allowed) {
        if (!actor.role().isOrgTier()) {
            // An id no org has filters to nothing, as a non-matching number did.
            if (orgId != null && requested == null) {
                return OrgScope.nothing();
            }
            return OrgScope.of(requested);
        }
        if (allowed.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null) {
            if (requested == null || !allowed.contains(requested)) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
            }
            return OrgScope.of(requested);
        }
        return OrgScope.of(allowed);
    }

    private AdminOrgScope() {
    }
}
