package kr.ac.pusan.pickle.oauth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;
import kr.ac.pusan.pickle.user.UserPosition;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code OauthCompleteRequest} — the onboarding form for a
 * Google account that has no local account yet.
 *
 * <p>Everything signup collects except the address and the password: the
 * address comes from the verified identity the registration token stands for,
 * and there is no password. Consents are here rather than gathered afterwards
 * so account creation and consent recording stay in one transaction, which is
 * what stops an account existing that has agreed to nothing.
 */
public record OauthCompleteRequest(
        @NotBlank @Size(max = 128) String registrationToken,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name,

        @NotNull(message = "직책을 선택해 주세요.")
        UserPosition position,

        @Size(max = 20, message = "학번은 20자 이하여야 합니다.")
        @Nullable String studentNo,

        @NotBlank(message = "소속을 선택해 주세요.")
        @Size(max = 32, message = "소속 코드가 올바르지 않습니다.")
        String departmentCode,

        @NotEmpty(message = "약관 동의가 필요합니다.")
        @Valid
        List<ConsentInput> consents) {
}
