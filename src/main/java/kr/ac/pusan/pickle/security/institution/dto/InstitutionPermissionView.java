package kr.ac.pusan.pickle.security.institution.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;

/** Stored choices and their independent source revision, rather than a role approximation. */
public record InstitutionPermissionView(InstitutionPermissionMode mode, List<String> work,
        Map<InstitutionWorkGroup, InstitutionPermissionScopeView> scopes, long revision) {

    public static InstitutionPermissionView from(InstitutionAssignment assignment) {
        Map<InstitutionWorkGroup, InstitutionPermissionScopeView> scopes = new LinkedHashMap<>();
        assignment.scopes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> scopes.put(entry.getKey(),
                new InstitutionPermissionScopeView(InstitutionWorkspaceScopeMode.valueOf(entry.getValue().mode().name()),
                        entry.getValue().workspaceIds().stream().sorted().toList())));
        return new InstitutionPermissionView(InstitutionPermissionMode.valueOf(assignment.mode().name()),
                assignment.work().stream().map(InstitutionWork::id).sorted().toList(), Map.copyOf(scopes), assignment.revision());
    }

    public InstitutionAssignment toAssignment(UUID account,UUID institution) {
        Map<InstitutionWorkGroup,kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope> converted=new LinkedHashMap<>();
        scopes.forEach((group,scope)->converted.put(group,scope.mode()==InstitutionWorkspaceScopeMode.ALL
                ? kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.all()
                : kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.listed(java.util.Set.copyOf(scope.workspaceIds()))));
        return new InstitutionAssignment(account,institution,InstitutionAssignment.Mode.valueOf(mode.name()),
                work.stream().map(id->InstitutionWork.fromId(id).orElseThrow()).collect(java.util.stream.Collectors.toSet()),converted,revision);
    }

    public record InstitutionPermissionScopeView(InstitutionWorkspaceScopeMode mode, List<UUID> workspaceIds) { }
}
