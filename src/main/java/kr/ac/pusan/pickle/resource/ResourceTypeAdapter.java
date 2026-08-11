package kr.ac.pusan.pickle.resource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;

/**
 * What the resource-generic machinery needs to know about one kind of resource.
 *
 * <p>The access list, the audit trail and the notification fan-out are already
 * written without naming VMs; what they lack is a way to ask a question of a
 * resource type they were not compiled against. This interface is that way, and
 * a second kind of resource joins by adding an implementation rather than by
 * editing the code that consumes it.
 *
 * <p>Implementations are Spring beans and are discovered as a list, so the
 * consuming services never enumerate the types themselves.
 */
public interface ResourceTypeAdapter {

    /** The type this adapter answers for. */
    ResourceType type();

    /**
     * The resource behind an id, reduced to what somebody without a grant may
     * be told; empty when this type has nothing under that id.
     *
     * <p>Empty is what the masking 404 is built on, so a type whose rows outlive
     * the thing they describe must still answer here: a destroyed VM keeps its
     * row and its access list, and the people on that list may still read its
     * history. "Gone" means the row is gone, not that the resource stopped
     * running.
     */
    Optional<ResourceIdentity> identify(long resourceId);

    /**
     * The same, reached by the identifier the resource wears outside the API
     * boundary. This is the form every request-scoped lookup takes: an id that
     * arrived over HTTP is resolved here once, and everything below works on
     * the internal id the returned identity carries.
     */
    Optional<ResourceIdentity> identifyByPublicId(UUID publicId);

    /**
     * What this type says when it refuses — the only part of the access rules
     * that is allowed to differ between types.
     */
    ResourceAccessMessages accessMessages();

    /** The names this type's access-list edits take in the audit trail. */
    ResourceAccessAudit accessAudit();

    /**
     * Every resource of this type the workspace owns, destroyed ones included.
     *
     * <p>Destroyed resources are in deliberately: their rows outlive them so
     * their history stays readable, and the access list is what decides who may
     * still read it, so a membership that ends must take those grants too.
     */
    List<Long> idsOwnedByWorkspace(long workspaceId);

    /**
     * How many of this type the workspace still holds that are not yet fully
     * destroyed — what stands between the workspace and its own deletion.
     *
     * <p>A resource on its way out counts: it still holds the platform's
     * resources until its destruction completes, and the workspace it belongs to
     * has to outlive it. Only the rows whose history is all that is left of them
     * are excluded.
     */
    long countLiveInWorkspace(long workspaceId);

    /**
     * The first {@code limit} rows of this type's contribution to the
     * inventory, plus how many rows the requester may see in total.
     *
     * <p>Visibility is the type's own: a row the requester holds no grant on
     * comes back limited rather than omitted, so they can see it exists and
     * ask. Count and rows must come from the same query, or a page can promise
     * more rows than the masking rules would ever produce.
     *
     * <p><b>Ordering is stated here as meaning, not as a sort key.</b> Newest
     * first by creation time; rows created in the same instant follow this
     * type's own stable order, descending by whatever it uses internally. The
     * caller merges several types by creation time alone and relies on a stable
     * sort to leave each type's own order intact, so the within-type tiebreak
     * never has to cross into Java — which is what lets an implementation order
     * by an internal id, or by a column named something else entirely.
     *
     * <p>The limit is a plain count rather than a {@code Pageable} on purpose:
     * a {@code Pageable} carries a sort expressed in the caller's vocabulary,
     * which would make every type name its columns the way the first one did.
     */
    InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit);

    /**
     * One type's answer to {@link #inventoryHead}: the head of its ordered
     * rows, and the total it was taken from.
     */
    record InventoryHead(List<ResourceSummaryResponse> rows, long totalElements) {

        /** Nothing of this type is visible to the requester. */
        public static InventoryHead empty() {
            return new InventoryHead(List.of(), 0);
        }
    }
}
