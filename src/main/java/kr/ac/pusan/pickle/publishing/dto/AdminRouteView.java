package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.RouteStatus;

/** Contract schema {@code AdminRouteView} — a route with VM/group/org context. */
public record AdminRouteView(
        Long id,
        String fqdn,
        DomainKind domainKind,
        Long vmId,
        String vmName,
        Long groupId,
        String groupName,
        Long orgId,
        String orgName,
        int targetPort,
        String protocol,
        RouteStatus status,
        Long appliedGeneration,
        Instant appliedAt,
        String lastError,
        Instant updatedAt) {
}
