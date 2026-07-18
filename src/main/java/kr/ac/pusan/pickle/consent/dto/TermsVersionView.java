package kr.ac.pusan.pickle.consent.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.consent.TermsDocType;
import kr.ac.pusan.pickle.consent.TermsVersion;

/** Contract schema {@code TermsVersionView} — current-version metadata (no body). */
public record TermsVersionView(TermsDocType docType, int version, String title, Instant effectiveAt) {

    public static TermsVersionView from(TermsVersion version) {
        return new TermsVersionView(version.getDocType(), version.getVersion(), version.getTitle(),
                version.getEffectiveAt());
    }
}
