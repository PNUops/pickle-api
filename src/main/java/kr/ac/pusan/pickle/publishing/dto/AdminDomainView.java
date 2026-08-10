package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminDomainView} (= DomainSummary + VM/workspace/org context
 * + route/cert status).
 *
 * <p>{@code releasedAt}/{@code reservedUntil} carry the same meaning and the
 * same server-side computation as on the user summary: a released platform
 * subdomain keeps {@link DomainStatus#ACTIVE} while it holds its name through
 * the grace, so this pair is the only axis that tells an admin why a name is
 * occupied.</p>
 */
public record AdminDomainView(
        Long id,
        Long vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        Instant createdAt,
        String vmName,
        Long workspaceId,
        String workspaceName,
        Long orgId,
        String orgName,
        @Nullable RouteStatus routeStatus,
        @Nullable CertificateStatus certificateStatus,
        @Nullable Instant updatedAt) {
}
