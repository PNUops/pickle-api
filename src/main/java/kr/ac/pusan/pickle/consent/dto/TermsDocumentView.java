package kr.ac.pusan.pickle.consent.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.consent.TermsDocType;
import kr.ac.pusan.pickle.consent.TermsVersion;

/** Contract schema {@code TermsDocumentView} — full current document incl. markdown body. */
public record TermsDocumentView(TermsDocType docType, int version, String title, String body,
        Instant effectiveAt) {

    public static TermsDocumentView from(TermsVersion version) {
        return new TermsDocumentView(version.getDocType(), version.getVersion(), version.getTitle(),
                version.getBody(), version.getEffectiveAt());
    }
}
