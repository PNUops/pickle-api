package kr.ac.pusan.pickle.resource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
     * This type's contribution to the inventory the requester may see, with the
     * same visibility rules the type's own list endpoint applies: a row the
     * requester holds no grant on comes back limited rather than omitted, so
     * they can see it exists and ask.
     */
    Page<ResourceSummaryResponse> page(AuthenticatedUser actor, UUID workspaceId, Pageable pageable);
}
