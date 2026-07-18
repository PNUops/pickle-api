package kr.ac.pusan.pickle.consent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Contract schema {@code ConsentUpdateRequest} — re-consent to revised documents. */
public record ConsentUpdateRequest(
        @NotEmpty(message = "동의할 문서를 지정해 주세요.")
        @Valid
        List<ConsentInput> consents) {
}
