package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.RouteStatus;

/**
 * Contract schema {@code AdminDomainView} (= DomainSummary + VM/group/org context
 * + route/cert status).
 */
public record AdminDomainView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        String rootDomain,
        DomainStatus status,
        Instant verifiedAt,
        Instant createdAt,
        String vmName,
        Long groupId,
        String groupName,
        Long orgId,
        String orgName,
        RouteStatus routeStatus,
        CertificateStatus certificateStatus,
        Instant updatedAt) {
}
