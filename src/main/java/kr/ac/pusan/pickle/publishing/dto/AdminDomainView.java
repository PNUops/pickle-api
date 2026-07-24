package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminDomainView} (= DomainSummary + VM/group/org context
 * + route/cert status).
 */
public record AdminDomainView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        Instant createdAt,
        String vmName,
        Long groupId,
        String groupName,
        Long orgId,
        String orgName,
        @Nullable RouteStatus routeStatus,
        @Nullable CertificateStatus certificateStatus,
        @Nullable Instant updatedAt) {
}
