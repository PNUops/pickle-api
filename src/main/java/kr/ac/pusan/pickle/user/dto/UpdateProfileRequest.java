package kr.ac.pusan.pickle.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdateProfileRequest} — PUT /me/profile.
 *
 * <p>The same three fields signup collects, because this is how an account
 * created before they existed fills them in, and how anyone corrects a 소속
 * after transferring. The cross-field rule (학번 for a student position) and
 * the department lookup are ProfileValidator's, exactly as at signup.
 */
public record UpdateProfileRequest(
        @NotNull(message = "직책을 선택해 주세요.")
        UserPosition position,

        @Size(max = 20, message = "학번은 20자 이하여야 합니다.")
        @Nullable String studentNo,

        @NotBlank(message = "소속을 선택해 주세요.")
        @Size(max = 32, message = "소속 코드가 올바르지 않습니다.")
        String departmentCode) {
}
