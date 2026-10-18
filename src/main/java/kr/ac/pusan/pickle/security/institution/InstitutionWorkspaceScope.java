package kr.ac.pusan.pickle.security.institution;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Stable IDs only; the target resolver remains responsible for workspace liveness. */
public record InstitutionWorkspaceScope(Mode mode, Set<UUID> workspaceIds) {
    public enum Mode { ALL, LISTED }

    public InstitutionWorkspaceScope {
        Objects.requireNonNull(mode, "mode");
        workspaceIds = Set.copyOf(workspaceIds);
        if (mode == Mode.ALL && !workspaceIds.isEmpty()) {
            throw new IllegalArgumentException("ALL must not contain listed workspace IDs");
        }
    }

    public static InstitutionWorkspaceScope all() {
        return new InstitutionWorkspaceScope(Mode.ALL, Set.of());
    }

    public static InstitutionWorkspaceScope listed(Collection<UUID> workspaceIds) {
        return new InstitutionWorkspaceScope(Mode.LISTED, Set.copyOf(workspaceIds));
    }

    public boolean includes(@Nullable UUID workspaceId) {
        return workspaceId != null && (mode == Mode.ALL || workspaceIds.contains(workspaceId));
    }
}
