package kr.ac.pusan.pickle.resource.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ResourceSummary}: one row of the type-agnostic
 * inventory, carrying only what every resource has.
 *
 * <p>Deliberately thin. Anything particular to a kind of resource -- a VM's
 * specification, an API key's expiry -- is read from that type's own detail
 * endpoint; putting it here would make this schema grow with every type and
 * force every consumer to know them all.
 *
 * <p>{@code status} is the type's own state as a string rather than a shared
 * enum: the states a VM moves through and the states a key moves through have
 * nothing in common, and flattening them into one vocabulary would invent a
 * meaning neither side has.
 */
public record ResourceSummaryResponse(
        Long id,
        ResourceType type,
        String name,
        @Nullable String displayName,
        String status,
        Long workspaceId,
        String workspaceName,
        /** True when the caller may see that it exists but not what it is. */
        boolean accessLimited,
        /** Who to ask for access, shown with a limited row. */
        List<String> ownerNames,
        /** Whether the caller may manage this resource's access list. */
        boolean accessManageAllowed,
        Instant createdAt) {
}
