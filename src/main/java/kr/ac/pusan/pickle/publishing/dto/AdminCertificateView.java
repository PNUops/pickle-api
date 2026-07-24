package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.CertificateKind;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminCertificateView}. */
public record AdminCertificateView(
        Long id,
        CertificateKind kind,
        CertificateStatus status,
        String scope,
        @Nullable Long domainId,
        @Nullable Instant notAfter,
        @Nullable Integer daysUntilExpiry,
        @Nullable String lastError) {
}
