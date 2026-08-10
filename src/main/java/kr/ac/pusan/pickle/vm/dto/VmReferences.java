package kr.ac.pusan.pickle.vm.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The public identifiers of the rows a VM points at, resolved by the caller.
 *
 * <p>A VM carries its references as internal foreign keys, and the public face
 * of each is a column on the referenced row — so the join has to happen where
 * the query is, not in the view. This carries the result across rather than
 * five more positional {@code UUID} parameters, which are trivially swapped.
 *
 * <p>Every field is nullable because these are history-preserving joins: a
 * destroyed VM keeps its row after the workspace, org or account it named is
 * gone, and a reference that no longer resolves is reported as absent rather
 * than as a broken id.
 */
public record VmReferences(
        @Nullable UUID workspaceId,
        @Nullable UUID orgId,
        @Nullable UUID imageId,
        @Nullable UUID requestId,
        @Nullable UUID deleteRequestedById) {
}
