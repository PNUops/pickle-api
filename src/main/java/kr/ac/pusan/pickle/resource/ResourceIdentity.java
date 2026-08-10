package kr.ac.pusan.pickle.resource;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * As much of one resource as somebody without a grant may be told: that it
 * exists, what it is called, what state it is in, and whose workspace it
 * belongs to.
 *
 * <p>This is what a resource type hands the shared access machinery in place of
 * its own entity. Everything inside the resource — an address, a guest account,
 * a published port — is deliberately absent, so that code holding one of these
 * cannot leak past the limited view even by accident.
 *
 * @param id          the resource's id within its type
 * @param publicId    the identifier it wears outside the API boundary
 * @param workspaceId the workspace that owns it, and whose owners hold standing
 *                    rights over it
 * @param name        the name it is listed under
 * @param displayName the name its owners gave it, or null if they gave none
 * @param status      this type's own state vocabulary, as a plain string
 */
public record ResourceIdentity(long id, UUID publicId, long workspaceId, String name,
        @Nullable String displayName, String status) {
}
