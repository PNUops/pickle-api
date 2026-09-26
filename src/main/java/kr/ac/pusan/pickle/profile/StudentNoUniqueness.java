package kr.ac.pusan.pickle.profile;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * One 학번 belongs to one account (V131).
 *
 * <p>The answer to "taken" is a 422 field error on {@code studentNo}, the same
 * shape every other profile rule uses, so the console attaches it to the field
 * it already renders. It is an answer only authenticated callers get: the
 * signup paths ask {@link #isTaken} and quietly store no profile instead,
 * because their uniform response is what keeps an address from being probed and
 * a 학번 is no less probeable.
 *
 * <p>The check before the write is a courtesy that names the field. The unique
 * index is what holds, and {@link #isViolation} is how a caller that lost the
 * race recognises it and answers with the same error.
 */
@Component
public class StudentNoUniqueness {

    /** The index V131 creates; a violation of any other constraint is a real fault. */
    static final String INDEX = "users_student_no_key";

    private final UserRepository userRepository;

    public StudentNoUniqueness(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Throws 422 when an account other than {@code ownerId} holds {@code studentNo}. */
    public void requireAvailable(@Nullable String studentNo, Long ownerId) {
        if (studentNo != null && userRepository.existsByStudentNoIgnoreCaseAndIdNot(studentNo, ownerId)) {
            throw taken();
        }
    }

    /** For a caller with no account yet. */
    public boolean isTaken(@Nullable String studentNo) {
        return studentNo != null && userRepository.existsByStudentNoIgnoreCase(studentNo);
    }

    /**
     * Sets the profile a signup brought, unless its 학번 is already held — in
     * which case none of it is stored.
     *
     * <p>All or nothing because a student 직책 without its 학번 is a row the
     * CHECK refuses, and keeping 직책 while dropping 학번 would store a profile
     * the holder never gave. The console asks for the whole profile again after
     * sign-in, so nothing is lost but a step.
     */
    public void applyAtSignup(User user, @Nullable UserPosition position, @Nullable String studentNo,
            @Nullable String departmentCode, @Nullable String departmentOther) {
        String stored = ProfileValidator.normalizeStudentNo(position, studentNo);
        if (isTaken(stored)) {
            return;
        }
        user.setProfile(position, stored, departmentCode,
                ProfileValidator.normalizeDepartmentOther(departmentOther));
    }

    public static boolean isViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null && message.contains(INDEX);
    }

    public static ApiException taken() {
        return ApiException.validationFailed(List.of(new FieldValidationError("studentNo",
                "이미 다른 계정에 등록된 학번입니다. 학번이 맞다면 문의해 주세요.")));
    }
}
