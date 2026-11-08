package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import kr.ac.pusan.pickle.publishing.DomainDnsStatus;
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
 *
 * <p>{@code dnsStatus}/{@code dnsLastError}/{@code dnsAppliedAt}: where the
 * platform's own A record stands, the axis that answers "the vhost is
 * APPLIED, why does the name not resolve". Custom domains are always NONE.</p>
 */
public record AdminDomainView(
        UUID id,
        @Schema(description = "이 이름을 사용하는 VM이며, 외부 도메인은 VM에 연결되지 않아 null이에요.")
        @Nullable UUID vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        @Schema(description = "외부 도메인의 사용 기한이에요. "
                + "다른 종류는 null이에요.")
        @Nullable Instant renewDueAt,
        @Nullable Instant createdAt,
        @Schema(description = "이 이름을 사용하는 VM의 이름이며, 외부 도메인은 null이에요.")
        @Nullable String vmName,
        @Nullable UUID workspaceId,
        @Nullable String workspaceName,
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Nullable RouteStatus routeStatus,
        @Nullable CertificateStatus certificateStatus,
        @Nullable DomainDnsStatus dnsStatus,
        @Nullable String dnsLastError,
        @Nullable Instant dnsAppliedAt,
        @Nullable Instant updatedAt,
        boolean metadataVisible,
        java.util.Set<String> allowedWorkIds) {
}
