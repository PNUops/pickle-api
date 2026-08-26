package kr.ac.pusan.pickle.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdateProfileRequest} — PUT /me/profile
 * ({@code minProperties: 1}).
 *
 * <p>Presence-tracked rather than a record, and that is load-bearing. 직책 and
 * 소속 학과 became optional in v0.46.0, so a record could no longer tell "leave
 * this alone" from "clear this": the account screen sends only 이름 when the
 * display name changes, and a record would carry three nulls along with it and
 * wipe the profile without an error. Absent means unchanged, an explicit
 * {@code null} means clear.
 *
 * <p>이름 is the exception — present-but-null is refused (422) because
 * {@code users.name} is NOT NULL and an account with no name has nowhere to be
 * displayed. 학번 has no field of its own to clear against: it is derived from
 * the position by {@code ProfileValidator.normalizeStudentNo}, which drops it
 * for a position that does not carry one.
 */
public class UpdateProfileRequest {

    // Not @Nullable, unlike the two below: clearing 이름 is refused (422), so
    // the published schema must not offer null as a value. The same split as
    // UpdateOrgRequest, where name is required-if-present and description is
    // clearable.
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;
    private boolean nameSet;

    private @Nullable UserPosition position;
    private boolean positionSet;

    @Size(max = 20, message = "학번은 20자 이하여야 합니다.")
    private @Nullable String studentNo;
    private boolean studentNoSet;

    @Size(max = 32, message = "소속 코드가 올바르지 않습니다.")
    private @Nullable String departmentCode;
    private boolean departmentCodeSet;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSet = true;
    }

    @Schema(hidden = true)
    public boolean isNameSet() {
        return nameSet;
    }

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

    @Schema(hidden = true)
    public boolean isEmpty() {
        return !nameSet && !positionSet && !studentNoSet && !departmentCodeSet;
    }
}
