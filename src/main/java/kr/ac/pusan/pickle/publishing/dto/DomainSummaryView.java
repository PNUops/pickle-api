package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code DomainSummary}. */
public record DomainSummaryView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        Instant createdAt) {

    public static DomainSummaryView from(Domain domain) {
        return new DomainSummaryView(domain.getId(), domain.getVmId(), domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getCreatedAt());
    }
}
