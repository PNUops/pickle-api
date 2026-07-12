package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.Certificate;
import kr.ac.pusan.pickle.publishing.CertificateKind;
import kr.ac.pusan.pickle.publishing.CertificateStatus;

/** Contract schema {@code CertificateView} — a domain's certificate status. */
public record CertificateView(
        CertificateKind kind,
        CertificateStatus status,
        Instant notAfter,
        String lastError) {

    public static CertificateView from(Certificate certificate) {
        return new CertificateView(certificate.getKind(), certificate.getStatus(),
                certificate.getNotAfter(), certificate.getLastError());
    }
}
