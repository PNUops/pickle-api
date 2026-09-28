package kr.ac.pusan.pickle.admin.bulk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeFieldDiff;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * What one family of targets contributes to a bulk change: how to load them,
 * who may touch them, what a change would move, and how to write it.
 *
 * <p>The order of questions is fixed by {@link AdminBulkChangeService} and is
 * the same for preview and apply: reach, then fingerprint, then state, then
 * the write. A handler answers each with a value rather than an exception,
 * because the apply runs every target inside one transaction and an exception
 * thrown through a transactional proxy would mark the whole batch for
 * rollback.
 *
 * @param <T> the loaded target
 */
abstract class BulkChangeHandler<T> {

    /**
     * One target's judgment: why it cannot change, or the fields that would,
     * plus whatever the write needs to carry the judgment out unchanged.
     */
    record Judgement(@Nullable AdminBulkChangeReason reason,
            List<AdminBulkChangeFieldDiff> fields, @Nullable Object plan) {

        static Judgement refused(AdminBulkChangeReason reason) {
            return new Judgement(reason, List.of(), null);
        }

        static Judgement unchanged() {
            return new Judgement(null, List.of(), null);
        }

        static Judgement change(List<AdminBulkChangeFieldDiff> fields, @Nullable Object plan) {
            return new Judgement(null, List.copyOf(fields), plan);
        }

        boolean writes() {
            return reason == null && !fields.isEmpty();
        }
    }

    /** The kinds this handler serves. */
    abstract Set<AdminBulkChangeKind> kinds();

    /**
     * Request-level checks that hold for every target alike (a date before
     * today, a missing reason). Added to {@code errors}; the caller answers
     * 422 with all of them.
     */
    abstract void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors);

    /** The targets behind the request's ids, keyed by id; an id nothing answers to is absent. */
    abstract Map<UUID, T> load(AdminBulkChangeRequest request);

    /** The internal key targets are processed and locked in. */
    abstract long orderKey(T target);

    abstract String name(T target);

    /**
     * Whether this actor may reach and change this target at all: NOT_FOUND
     * where the single path masks, FORBIDDEN where it refuses a role, null
     * where the change may go on to be judged.
     */
    abstract @Nullable AdminBulkChangeReason accessRefusal(AuthenticatedUser actor, T target,
            AdminBulkChangeSpec change);

    /** Every value the change kind could touch, in a stable order. */
    abstract Map<String, Object> fingerprintValues(T target, AdminBulkChangeSpec change);

    abstract Judgement judge(AuthenticatedUser actor, T target, AdminBulkChangeSpec change,
            Instant now);

    /**
     * Takes the row lock and re-reads the target, before it is judged for
     * real. The default holds nothing: a family whose writes are compare-and-
     * set updates needs no lock beyond them.
     */
    void lock(T target) {
    }

    /** Whether writes of this family reach the gateway document and so spend a generation. */
    boolean bumpsGateway() {
        return false;
    }

    /**
     * Carries out a judgment that said a write is due. Null when it went
     * through; otherwise the reason the write itself refused, which is what a
     * compare-and-set that lost to a concurrent change answers.
     */
    abstract @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, T target,
            Judgement judgement, AdminBulkChangeSpec change, UUID batchId, String ip);

    /** A diff entry with both values reduced to their JSON spelling. */
    static AdminBulkChangeFieldDiff diff(ObjectMapper objectMapper, String field,
            @Nullable Object oldValue, @Nullable Object newValue) {
        return new AdminBulkChangeFieldDiff(field,
                objectMapper.valueToTree(BulkFingerprints.plain(oldValue)),
                objectMapper.valueToTree(BulkFingerprints.plain(newValue)));
    }
}
