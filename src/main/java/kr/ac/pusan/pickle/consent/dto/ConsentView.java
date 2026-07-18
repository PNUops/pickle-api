package kr.ac.pusan.pickle.consent.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.consent.TermsDocType;

/** Contract schema {@code ConsentView} — a recorded consent (used in a JPQL projection). */
public record ConsentView(TermsDocType docType, int version, Instant consentedAt) {
}
