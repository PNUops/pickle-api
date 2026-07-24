package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.Certificate;
import kr.ac.pusan.pickle.publishing.CertificateKind;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code CertificateView} — a domain's certificate status. */
public record CertificateView(
        CertificateKind kind,
        CertificateStatus status,
        @Nullable Instant notAfter,
        @Nullable String lastError) {

    public static CertificateView from(Certificate certificate) {
        return new CertificateView(certificate.getKind(), certificate.getStatus(),
                certificate.getNotAfter(), certificate.getLastError());
    }
}
