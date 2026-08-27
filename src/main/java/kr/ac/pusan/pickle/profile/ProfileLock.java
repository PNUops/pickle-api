package kr.ac.pusan.pickle.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.dto.UpdateProfileRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 직책·학번·소속 are write-once for the account holder: settable while empty,
 * fixed once filled, and changed after that only by an administrator
 * (operator decision, 2026-08-27).
 *
 * <p>The concern is a 학번 that is not the holder's. A lock does not stop
 * someone typing one — it stops them typing a second one, so what it really
 * buys is that the value stops moving once anyone might rely on it. That also
 * means <b>the first value is the one that sticks</b>, mistakes included, which
 * is why {@code V89} declined a unique constraint on 학번 (a typo would claim a
 * real student's number with no way back) and why the administrator path is
 * part of this change rather than a follow-up. Locking without it would be the
 * trap that comment describes.
 *
 * <p>이름 is deliberately outside the lock: it is a display string that
 * identifies nobody, and v0.46.0 added the ability to change it.
 *
 * <p>Enforced here rather than by a constraint or a trigger, because the
 * administrator writes to the same columns and a trigger cannot tell the two
 * callers apart.
 */
@Component
public class ProfileLock {

    /**
     * Throws 422 naming every locked field the request would move.
     *
     * <p>Re-sending a stored value is allowed. The console does resend — the
     * profile modal opens prefilled and submits every field it shows — so
     * treating "present" as "changed" would refuse an edit that only touches
     * 이름. What is refused is a <em>different</em> value, and clearing counts
     * as different.
     */
    public void enforce(User user, UpdateProfileRequest request) {
        List<FieldValidationError> errors = new ArrayList<>();
        if (request.isPositionSet()) {
            check(errors, "position", "직책", user.getPosition(), request.getPosition());
        }
        if (request.isStudentNoSet()) {
            check(errors, "studentNo", "학번", user.getStudentNo(), strip(request.getStudentNo()));
        }
        if (request.isDepartmentCodeSet()) {
            check(errors, "departmentCode", "소속", user.getDepartmentCode(),
                    request.getDepartmentCode());
        }
        if (request.isDepartmentOtherSet()) {
            check(errors, "departmentOther", "소속", user.getDepartmentOther(),
                    strip(request.getDepartmentOther()));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    /**
     * A stored value may not be replaced; an empty one may be filled.
     *
     * <p>{@code UserPosition} and {@code String} both compare correctly under
     * {@link Objects#equals}, so the two field kinds share this.
     */
    private void check(List<FieldValidationError> errors, String field, String label,
            @Nullable Object stored, @Nullable Object requested) {
        if (stored == null || Objects.equals(stored, requested)) {
            return;
        }
        errors.add(new FieldValidationError(field,
                label + "은(는) 입력한 뒤에는 직접 바꿀 수 없습니다. 변경이 필요하면 문의해 주세요."));
    }

    /**
     * Blank becomes null, matching what the write path stores.
     *
     * <p>Without this, submitting {@code " "} against a stored value would read
     * as a change and be refused with a message about needing an inquiry, when
     * the value it was compared against is what would have been stored anyway.
     */
    private static @Nullable String strip(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Whether a position change would drop a stored 학번.
     *
     * <p>{@code ProfileValidator.normalizeStudentNo} discards a 학번 that the
     * position does not carry, which is a silent write to a locked field: a
     * 학부생 with a 학번 who is allowed to set 직책 (they are not — it is locked
     * once set) would lose it. Kept as a guard rather than a comment because
     * the administrator path can move 직책, and there the drop is intended and
     * has to be visible in the audit entry.
     */
    public static boolean positionChangeDropsStudentNo(@Nullable UserPosition from,
            @Nullable UserPosition to, @Nullable String storedStudentNo) {
        return storedStudentNo != null && from != to
                && (to == null || !to.requiresStudentNo());
    }
}
