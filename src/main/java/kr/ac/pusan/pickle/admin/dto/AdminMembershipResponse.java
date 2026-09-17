package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;

/**
 * Contract schema {@code AdminMembershipResponse} — one workspace this account
 * belongs to, as the admin user detail reports it.
 *
 * <p>The holder's own {@code Membership} plus {@code vmOrgIds}, the
 * organisations that workspace has live machines in. A workspace carries no
 * organisation of its own, so this is derived and can name more than one. The
 * screen needs it because this read is not org-scoped while the VM list is.
 */
public record AdminMembershipResponse(
        UUID workspaceId,
        String workspaceName,
        WorkspaceKind workspaceKind,
        WorkspaceMemberRole role,
        List<UUID> vmOrgIds) {
}
