package kr.ac.pusan.pickle.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The two profile rules bean validation cannot state: 학번 is required exactly
 * for the student positions, and 소속 has to name a department the catalogue
 * knows.
 *
 * <p>Both run in the service layer, which puts them after the DTO checks and —
 * critically — <b>before the address is looked up</b> during signup. The
 * uniform 202 that hides whether an address is registered is only uniform if
 * every request-shape rejection happens first; a profile rule that ran after
 * the lookup would make the validation order itself the oracle.
 *
 * <p>Errors are collected rather than thrown one at a time so a form that has
 * two things wrong gets told both.
 */
@Component
public class ProfileValidator {

    /**
     * Deliberately permissive: 편입·재입학·교환학생 numbers do not share one
     * shape, and a pattern tight enough to be useful would reject real students.
     * This only refuses input that cannot be a 학번 at all.
     */
    private static final Pattern STUDENT_NO = Pattern.compile("^[A-Za-z0-9-]{4,20}$");

    private final ProfileOptionsService options;

    public ProfileValidator(ProfileOptionsService options) {
        this.options = options;
    }

    /** Throws 422 with one field error per broken rule. */
    public void validate(UserPosition position, @Nullable String studentNo, String departmentCode) {
        List<FieldValidationError> errors = new ArrayList<>();
        String trimmed = studentNo == null ? null : studentNo.strip();

        // Both branches are gated on the position needing a 학번 at all. Checking
        // the format for a position that discards the value produces an error on
        // a field the console has hidden: someone who typed a partial number as
        // a 학부생 and then switched to 교수 would get a 422 they cannot clear,
        // for a value normalizeStudentNo is about to throw away regardless.
        if (position != null && position.requiresStudentNo()) {
            if (trimmed == null || trimmed.isEmpty()) {
                errors.add(new FieldValidationError("studentNo", "학번을 입력해 주세요."));
            } else if (!STUDENT_NO.matcher(trimmed).matches()) {
                errors.add(new FieldValidationError("studentNo",
                        "학번 형식이 올바르지 않습니다. (영문·숫자·하이픈 4~20자)"));
            }
        }
        if (departmentCode != null && !options.isKnownDepartment(departmentCode)) {
            errors.add(new FieldValidationError("departmentCode", "소속을 다시 선택해 주세요."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    /**
     * The value to store: blank becomes null so a non-student never carries an
     * empty string, and the CHECK constraint sees the same thing the rule did.
     */
    public static @Nullable String normalizeStudentNo(UserPosition position, @Nullable String studentNo) {
        if (position != null && !position.requiresStudentNo()) {
            return null;
        }
        if (studentNo == null) {
            return null;
        }
        String trimmed = studentNo.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
