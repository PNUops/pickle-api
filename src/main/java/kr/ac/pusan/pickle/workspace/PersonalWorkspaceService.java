package kr.ac.pusan.pickle.workspace;

import kr.ac.pusan.pickle.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the implicit PERSONAL workspace (one OWNER row) when an account becomes
 * ACTIVE. Idempotent per user.
 */
@Service
public class PersonalWorkspaceService {
    private final kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public PersonalWorkspaceService(kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock, WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository) {
        this.administrationLock = administrationLock;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional
    public void ensurePersonalWorkspace(User user) {
        administrationLock.acquire();
        if (workspaceMemberRepository.existsByUserIdAndWorkspaceKind(user.getId(), WorkspaceKind.PERSONAL)) {
            return;
        }
        Workspace workspace = workspaceRepository.save(
                new Workspace(WorkspaceKind.PERSONAL, user.getName(), null));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, user.getId(), WorkspaceMemberRole.OWNER));
    }

}
