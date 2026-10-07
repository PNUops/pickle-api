package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Objects;
import kr.ac.pusan.pickle.admin.dto.AdminUpdateProfileRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.profile.StudentNoUniqueness;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserStatusChange;
import kr.ac.pusan.pickle.user.UserStatusChangeRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Proposed values and restoration rules shared by observation and mutation. */
@Component
class AdminUserProfileChanges {
    private final ProfileValidator validator;
    private final StudentNoUniqueness uniqueness;

    AdminUserProfileChanges(ProfileValidator validator, StudentNoUniqueness uniqueness) {
        this.validator = validator;
        this.uniqueness = uniqueness;
    }

    record Candidate(@Nullable UserPosition position, @Nullable String studentNo,
            @Nullable String departmentCode, @Nullable String departmentOther) { }

    Candidate candidate(User user, AdminUpdateProfileRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("position", "수정할 값을 하나 이상 보내 주세요.")));
        }
        UserPosition position = request.isPositionSet() ? request.getPosition() : user.getPosition();
        String studentNo = request.isStudentNoSet() ? request.getStudentNo() : user.getStudentNo();
        String departmentCode = request.isDepartmentCodeSet() ? request.getDepartmentCode() : user.getDepartmentCode();
        String departmentOther = request.isDepartmentOtherSet() ? request.getDepartmentOther() : user.getDepartmentOther();
        validator.validate(position, studentNo, departmentCode, departmentOther,
                request.isDepartmentCodeSet() && !Objects.equals(user.getDepartmentCode(), departmentCode));
        String stored = ProfileValidator.normalizeStudentNo(position, studentNo);
        uniqueness.requireAvailable(stored, user.getId());
        return new Candidate(position, stored, departmentCode, ProfileValidator.normalizeDepartmentOther(departmentOther));
    }

    static UserStatus restoredStatus(User user, UserStatusChangeRepository changes) {
        return changes.findFirstByUserIdAndToStatusOrderByChangedAtDescIdDesc(user.getId(), UserStatus.DISABLED)
                .map(UserStatusChange::getFromStatus).orElse(UserStatus.ACTIVE);
    }
}
