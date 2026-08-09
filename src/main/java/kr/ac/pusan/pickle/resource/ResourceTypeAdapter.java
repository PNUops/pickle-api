package kr.ac.pusan.pickle.resource;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;

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
}
