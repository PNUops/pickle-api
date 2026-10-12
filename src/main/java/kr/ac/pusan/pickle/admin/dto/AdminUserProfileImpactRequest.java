package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/** An observation of the proposed change, with no reservation or mutation. */
public record AdminUserProfileImpactRequest(@NotNull(message = "미리보기 종류를 지정해 주세요.") AdminUserProfileImpactAction action,
        @Valid @Nullable AdminUpdateProfileRequest profile) {
    @Schema(name = "AdminUserProfileImpactAction")
    public enum AdminUserProfileImpactAction { PROFILE, ENABLE }
}
