package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DnsDomainView}: a name issued on its own, with no VM
 * behind it.
 *
 * <p>Deliberately not {@code DomainSummaryView}. That schema carries a required
 * {@code vmId} and a {@code dnsStatus} describing the one A record the platform
 * writes for a name it serves; neither means anything here, and widening it
 * would make every reader of a served domain handle a null VM that its own
 * paths can never produce.</p>
 *
 * <p>{@code accessLimited} is the restricted form every resource kind uses: the
 * row is listed so its existence and owner are visible, and the parts only a
 * grant opens are absent — which is what {@code ownerNames} is for, since a row
 * somebody can see but not open is only useful if it says who to ask. {@code renewDueAt} is always present — a name of this
 * kind cannot exist without a deadline — and {@code reservedUntil} appears only
 * while a released name is still being held for its workspace.</p>
 *
 * <p>{@code myResourceRole} is the rung the reader holds on this name, null
 * when the access list does not name them — a workspace owner with no grant
 * included, whose standing rights arrive as {@code accessManageAllowed}
 * instead. Screens need it because renewing and editing records take EDITOR
 * while releasing takes a manager: without the rung a reader below those sees
 * the buttons and learns from a 403 after pressing.</p>
 */
public record DnsDomainView(
        UUID id,
        String fqdn,
        String rootDomain,
        DomainStatus status,
        Instant renewDueAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        Instant createdAt,
        UUID workspaceId,
        String workspaceName,
        boolean accessLimited,
        boolean accessManageAllowed,
        @Schema(description = "요청자가 이 도메인의 접근 목록에서 받은 등급. "
                + "목록에 없으면 null입니다.")
        @Nullable ResourceRole myResourceRole,
        java.util.List<String> ownerNames,
        int recordSetCount) {
}
