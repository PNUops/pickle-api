package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DomainDetail} (= DomainSummary + verification). Flat
 * JSON object: the summary fields plus the custom-domain verification block
 * (null for platform subdomains).
 */
public record DomainDetailView(
        UUID id,
        UUID vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        Instant createdAt,
        @Nullable DomainVerificationView verification) {
}
