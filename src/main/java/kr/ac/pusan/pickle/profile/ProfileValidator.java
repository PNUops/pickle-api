package kr.ac.pusan.pickle.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.user.User;
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

    /**
     * Throws 422 with one field error per broken rule.
     *
     * <p>직책 and 소속 are both optional since v0.46.0 — an account that has
     * filled in neither is an ordinary state, not an incomplete one — so every
     * rule below is written to pass on null.
     *
     * <p>소속 has two shapes and the position picks between them. A student
     * belongs to a 학과 and chooses a catalogue code. A 교수, 연구원 or 직원 may
     * belong to a 연구소 or a 부서 that no 학과 list contains, so they write it
     * out. A student whose 학과 is not listed uses both: the {@code OTHER} code
     * and the written value.
     *
     * <p><b>That pairing is the console's to offer, not this method's to
     * enforce</b>, and deliberately so. Every account that filled in a profile
     * before v0.46.0 was made to choose a 학과 code, 교수 and 직원 included, so
     * refusing a code beside a non-student position would refuse the rows that
     * already exist — including on a request that only changes 이름, since the
     * rules run against the merge. What is enforced is the one combination the
     * CHECK constraint refuses, because that one would be a 500 at flush.
     */
    public void validate(@Nullable UserPosition position, @Nullable String studentNo,
            @Nullable String departmentCode, @Nullable String departmentOther) {
        validate(position, studentNo, departmentCode, departmentOther, true);
    }

    /**
     * As above, with {@code verifyDepartmentCode} false when the code reaching
     * here is the stored one rather than one the request is introducing.
     *
     * <p>The catalogue moves. It is a file with a host override precisely so a
     * yearly reorganisation can change it, and a code that was valid when it
     * was stored can stop being listed. Checking a stored code on every write
     * turns that into a total lockout: the value is judged against the merge,
     * so an account whose 소속 has left the catalogue gets a 422 on
     * {@code departmentCode} for a request that only changes 이름 — and under
     * write-once it can neither pick another code nor clear the old one. Before
     * the lock that account could pick something else and move on.
     *
     * <p>So catalogue membership is a rule about what is being introduced, not
     * about what is already there. A stored code passed this check once; the
     * list changing afterwards is not the holder's doing.
     */
    public void validate(@Nullable UserPosition position, @Nullable String studentNo,
            @Nullable String departmentCode, @Nullable String departmentOther,
            boolean verifyDepartmentCode) {
        List<FieldValidationError> errors = new ArrayList<>();
        String trimmed = studentNo == null ? null : studentNo.strip();
        String otherTrimmed = departmentOther == null ? null : departmentOther.strip();

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
        if (verifyDepartmentCode && departmentCode != null
                && !options.isKnownDepartment(departmentCode)) {
            errors.add(new FieldValidationError("departmentCode", "소속을 다시 선택해 주세요."));
        }
        // A code that is not OTHER already names the 소속, so a written value
        // beside it is a second answer to the same question and the CHECK
        // refuses it. Saying so here is the difference between a field error
        // and a 500 at flush.
        if (otherTrimmed != null && !otherTrimmed.isEmpty()
                && departmentCode != null && !DepartmentCatalog.OTHER.equals(departmentCode)) {
            errors.add(new FieldValidationError("departmentOther",
                    "목록에서 고른 소속과 직접 입력한 소속 중 하나만 보낼 수 있습니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    /**
     * Blank becomes null, so a 소속 nobody wrote is absent rather than empty.
     *
     * <p>{@code chk_users_department_other} and {@link User#isProfileComplete}
     * both read this column as "was it answered", and an empty string answers
     * nothing while satisfying both.
     */
    public static @Nullable String normalizeDepartmentOther(@Nullable String departmentOther) {
        if (departmentOther == null) {
            return null;
        }
        String trimmed = departmentOther.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The value to store: blank becomes null so a non-student never carries an
     * empty string, and the CHECK constraint sees the same thing the rule did.
     *
     * <p>A null position drops the 학번 as well, and that is not cosmetic.
     * Since v0.46.0 직책 is optional, so {@code position == null} reaches here
     * from an ordinary signup — and both the format check in {@link #validate}
     * and the branch below used to be gated on {@code position != null},
     * which left an unvalidated, unnormalised string as the only thing that
     * ever reached the column. {@code chk_users_student_no} cannot catch it
     * either: it is an implication whose first disjunct is {@code position is
     * null}, so it is satisfied by construction. A 학번 with no 직책 to hang
     * off is not a fact about anyone, so it is dropped exactly as a 교수's is.
     */
    public static @Nullable String normalizeStudentNo(@Nullable UserPosition position,
            @Nullable String studentNo) {
        if (position == null || !position.requiresStudentNo()) {
            return null;
        }
        if (studentNo == null) {
            return null;
        }
        String trimmed = studentNo.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
