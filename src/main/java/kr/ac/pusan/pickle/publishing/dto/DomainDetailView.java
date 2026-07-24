package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DomainDetail} (= DomainSummary + verification). Flat
 * JSON object: the summary fields plus the custom-domain verification block
 * (null for platform subdomains).
 */
public record DomainDetailView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        Instant createdAt,
        @Nullable DomainVerificationView verification) {
}
