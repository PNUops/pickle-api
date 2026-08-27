package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminUpdateProfileRequest} — PATCH
 * /admin/users/{userId}/profile ({@code minProperties: 1}).
 *
 * <p>The other half of the write-once lock: 직책·학번·소속 stop moving for the
 * account holder, so someone has to be able to move them, and the first value
 * being permanent is what makes that necessary rather than convenient
 * ({@code V89} declined a unique constraint on 학번 for exactly this reason —
 * a typo would claim a real student's number with no way back).
 *
 * <p>Presence-tracked for the same reason {@code UpdateProfileRequest} is: an
 * administrator correcting 학번 alone must not blank 직책 and 소속 by omission.
 * Absent means unchanged, an explicit {@code null} means clear — and clearing
 * is allowed here, unlike on the holder's own path, because a value entered by
 * mistake has to be removable and not only replaceable.
 *
 * <p>이름 is not here. It is already the holder's to change and putting it on
 * an administrator endpoint would widen the write surface for no case anyone
 * has.
 */
@Schema(minProperties = 1)
public class AdminUpdateProfileRequest {

    private @Nullable UserPosition position;
    private boolean positionSet;

    @Size(max = 20, message = "학번은 20자 이하여야 합니다.")
    private @Nullable String studentNo;
    private boolean studentNoSet;

    @Size(max = 32, message = "소속 코드가 올바르지 않습니다.")
    private @Nullable String departmentCode;
    private boolean departmentCodeSet;

    @Size(max = 100, message = "소속은 100자 이하여야 합니다.")
    private @Nullable String departmentOther;
    private boolean departmentOtherSet;

    /** Why the correction was made; recorded in the audit entry. */
    @Size(max = 200, message = "사유는 200자 이하여야 합니다.")
    private @Nullable String reason;

    public @Nullable UserPosition getPosition() {
        return position;
    }

    public void setPosition(@Nullable UserPosition position) {
        this.position = position;
        this.positionSet = true;
    }

    @Schema(hidden = true)
    public boolean isPositionSet() {
        return positionSet;
    }

    public @Nullable String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(@Nullable String studentNo) {
        this.studentNo = studentNo;
        this.studentNoSet = true;
    }

    @Schema(hidden = true)
    public boolean isStudentNoSet() {
        return studentNoSet;
    }

    public @Nullable String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(@Nullable String departmentCode) {
        this.departmentCode = departmentCode;
        this.departmentCodeSet = true;
    }

    @Schema(hidden = true)
    public boolean isDepartmentCodeSet() {
        return departmentCodeSet;
    }

    public @Nullable String getDepartmentOther() {
        return departmentOther;
    }

    public void setDepartmentOther(@Nullable String departmentOther) {
        this.departmentOther = departmentOther;
        this.departmentOtherSet = true;
    }

    @Schema(hidden = true)
    public boolean isDepartmentOtherSet() {
        return departmentOtherSet;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public void setReason(@Nullable String reason) {
        this.reason = reason;
    }

    @Schema(hidden = true)
    public boolean isEmpty() {
        return !positionSet && !studentNoSet && !departmentCodeSet && !departmentOtherSet;
    }
}
