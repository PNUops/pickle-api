package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.CertificateKind;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminCertificateView}. */
public record AdminCertificateView(
        UUID id,
        CertificateKind kind,
        CertificateStatus status,
        String scope,
        @Nullable UUID domainId,
        @Nullable Instant notAfter,
        @Nullable Integer daysUntilExpiry,
        @Nullable String lastError) {
}
