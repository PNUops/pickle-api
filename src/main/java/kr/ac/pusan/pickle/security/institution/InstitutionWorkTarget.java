package kr.ac.pusan.pickle.security.institution;

import java.util.UUID;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Only server-resolved live targets may enter the evaluator. Each work boundary
 * requires its own relations; explicit resource support needs a verified kind and
 * workspace without inventing an institution for an institution-less resource.
 * The resolver determines a resource's actual institution and fixed group from its
 * relationships, never from a client kind. Audience qualification is separate from
 * ownership: workspaces have no institution owner.
 */
public record InstitutionWorkTarget(@Nullable UUID institutionId, @Nullable InstitutionWorkGroup group,
        @Nullable UUID workspaceId, Set<UUID> audienceInstitutions) {

    public InstitutionWorkTarget { audienceInstitutions = Set.copyOf(audienceInstitutions); }

    public static InstitutionWorkTarget resource(@Nullable UUID institutionId,
            @Nullable InstitutionWorkGroup group, @Nullable UUID workspaceId) {
        return new InstitutionWorkTarget(institutionId, group, workspaceId, Set.of());
    }

    public static InstitutionWorkTarget workspace(UUID workspaceId) {
        return new InstitutionWorkTarget(null, InstitutionWorkGroup.WORKSPACE, workspaceId, Set.of());
    }

    /** The resolver has separately verified the existing audience qualification for these institutions. */
    public static InstitutionWorkTarget qualifiedWorkspace(UUID workspaceId, Set<UUID> audienceInstitutions) {
        return new InstitutionWorkTarget(null, InstitutionWorkGroup.WORKSPACE, workspaceId, audienceInstitutions);
    }

    public static InstitutionWorkTarget institution(UUID institutionId) {
        return new InstitutionWorkTarget(institutionId, null, null, Set.of());
    }

    public static InstitutionWorkTarget global() {
        return new InstitutionWorkTarget(null, null, null, Set.of());
    }
}
