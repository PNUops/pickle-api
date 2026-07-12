package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.CertificateKind;
import kr.ac.pusan.pickle.publishing.CertificateStatus;

/** Contract schema {@code AdminCertificateView}. */
public record AdminCertificateView(
        Long id,
        CertificateKind kind,
        CertificateStatus status,
        String scope,
        Long domainId,
        Instant notAfter,
        Integer daysUntilExpiry,
        String lastError) {
}
