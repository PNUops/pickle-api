package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;

/** Contract schema {@code DomainSummary}. */
public record DomainSummaryView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        String rootDomain,
        DomainStatus status,
        Instant verifiedAt,
        Instant createdAt) {

    public static DomainSummaryView from(Domain domain) {
        return new DomainSummaryView(domain.getId(), domain.getVmId(), domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getCreatedAt());
    }
}
