package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DomainSummary}. {@code releasedAt}/{@code
 * reservedUntil} are set while a released platform subdomain is holding its
 * name through the reservation grace; the server computes {@code
 * reservedUntil} (releasedAt + grace days) so clients never have to know the
 * grace setting.
 */
public record DomainSummaryView(
        UUID id,
        UUID vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        Instant createdAt) {
}
