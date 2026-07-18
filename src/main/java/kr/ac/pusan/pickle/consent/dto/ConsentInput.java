package kr.ac.pusan.pickle.consent.dto;

import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.consent.TermsDocType;

/** Contract schema {@code ConsentInput} — a (docType, version) the user consents to. */
public record ConsentInput(
        @NotNull(message = "문서 종류를 지정해 주세요.")
        TermsDocType docType,
        int version) {
}
