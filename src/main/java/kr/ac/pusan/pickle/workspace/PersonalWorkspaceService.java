package kr.ac.pusan.pickle.workspace;

import java.util.Locale;
import kr.ac.pusan.pickle.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the implicit PERSONAL workspace (one OWNER row) when an account becomes
 * ACTIVE. Idempotent per user.
 */
@Service
public class PersonalWorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public PersonalWorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional
    public void ensurePersonalWorkspace(User user) {
        if (workspaceMemberRepository.existsByUserIdAndWorkspaceKind(user.getId(), WorkspaceKind.PERSONAL)) {
            return;
        }
        String slug = uniqueSlug(slugify(user.getEmail().split("@", 2)[0], user.getId()));
        Workspace workspace = workspaceRepository.save(new Workspace(WorkspaceKind.PERSONAL, user.getName(), slug, null));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, user.getId(), WorkspaceMemberRole.OWNER));
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (workspaceRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** Lowercase/digit/hyphen slug (default-subdomain constraint). */
    private static String slugify(String source, Long fallbackId) {
        String slug = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "user-" + fallbackId : slug;
    }
}
