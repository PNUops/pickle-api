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
        UUID vmId,
        DomainKind kind,
        String fqdn,
        @Nullable String rootDomain,
        DomainStatus status,
        @Nullable Instant verifiedAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        @Schema(description = "사용 기한. 외부 도메인만 갖습니다 — 다른 종류는 가상머신이나 "
                + "소유자의 DNS가 수명을 정하므로 null입니다.")
        @Nullable Instant renewDueAt,
        Instant createdAt,
        String vmName,
        UUID workspaceId,
        String workspaceName,
        UUID orgId,
        String orgName,
        @Nullable RouteStatus routeStatus,
        @Nullable CertificateStatus certificateStatus,
        DomainDnsStatus dnsStatus,
        @Nullable String dnsLastError,
        @Nullable Instant dnsAppliedAt,
        @Nullable Instant updatedAt) {
}
