package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminRouteView} — a route with VM/workspace/org context. */
public record AdminRouteView(
        UUID id,
        UUID domainId,
        String fqdn,
        DomainKind domainKind,
        UUID vmId,
        String vmName,
        UUID workspaceId,
        String workspaceName,
        UUID orgId,
        String orgName,
        int targetPort,
        String protocol,
        RouteStatus status,
        @Nullable Long appliedGeneration,
        @Nullable Instant appliedAt,
        @Nullable String lastError,
        @Nullable Instant updatedAt) {
}
