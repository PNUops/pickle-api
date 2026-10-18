package kr.ac.pusan.pickle.security.institution.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;

public record InstitutionWorkspaceScopeInput(
        @NotNull InstitutionWorkspaceScopeMode mode,
        @NotNull @Size(max = 2000) List<@NotNull UUID> workspaceIds) {

    public InstitutionWorkspaceScope toScope() {
        if (mode == null || workspaceIds == null || new HashSet<>(workspaceIds).size() != workspaceIds.size()) {
            throw new IllegalArgumentException("A workspace scope requires a mode and unique IDs");
        }
        return new InstitutionWorkspaceScope(InstitutionWorkspaceScope.Mode.valueOf(mode.name()), new HashSet<>(workspaceIds));
    }
}
