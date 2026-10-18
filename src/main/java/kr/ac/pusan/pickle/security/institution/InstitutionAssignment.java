package kr.ac.pusan.pickle.security.institution;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.user.UserRole;

/** One complete grant source; work and scopes never come from different assignments. */
public record InstitutionAssignment(UUID accountId, UUID institutionId, Mode mode,
        Set<InstitutionWork> work, Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes, long revision) {
    public enum Mode { FULL, SELECTED }

    public InstitutionAssignment {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(mode, "mode");
        work = Set.copyOf(work);
        scopes = Map.copyOf(scopes);
        if (revision < 0) { throw new IllegalArgumentException("revision must not be negative"); }
        if (mode == Mode.FULL) {
            if (!work.isEmpty() || !scopes.isEmpty()) {
                throw new IllegalArgumentException("FULL has catalogue work and ALL scopes, not selected values");
            }
        } else {
            if (work.isEmpty() || work.stream().anyMatch(item -> !item.selectable())) {
                throw new IllegalArgumentException("SELECTED requires selectable work without delegation");
            }
            Set<InstitutionWorkGroup> groups = work.stream().map(InstitutionWork::group)
                    .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
            if (!groups.equals(scopes.keySet())) {
                throw new IllegalArgumentException("Every selected group must have exactly one shared scope");
            }
        }
    }

    public static InstitutionAssignment full(UUID accountId, UUID institutionId, long revision) {
        return new InstitutionAssignment(accountId, institutionId, Mode.FULL, Set.of(), Map.of(), revision);
    }

    public static InstitutionAssignment selected(UUID accountId, UUID institutionId,
            Set<InstitutionWork> work, Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes, long revision) {
        return new InstitutionAssignment(accountId, institutionId, Mode.SELECTED, work, scopes, revision);
    }

    public boolean includes(InstitutionWork candidate) {
        return mode == Mode.FULL ? InstitutionWorkCatalogue.includedInFull(candidate) : work.contains(candidate);
    }

    public UserRole displayRole() {
        if (mode == Mode.FULL) { return UserRole.ORG_ADMIN; }
        return work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER;
    }

    boolean includesWorkspace(InstitutionWorkGroup group, UUID workspaceId) {
        return workspaceId != null && (mode == Mode.FULL || scopes.get(group).includes(workspaceId));
    }
}
