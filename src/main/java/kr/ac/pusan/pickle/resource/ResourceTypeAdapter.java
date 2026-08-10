package kr.ac.pusan.pickle.resource;

import java.util.List;
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
    Page<ResourceSummaryResponse> page(AuthenticatedUser actor, Long workspaceId, Pageable pageable);
}
