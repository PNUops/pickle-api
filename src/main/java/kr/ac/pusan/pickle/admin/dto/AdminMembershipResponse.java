package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;

/**
 * Contract schema {@code AdminMembership} — one workspace this account belongs
 * to, as the admin user detail reports it.
 *
 * <p>Same fields as the holder's own {@code Membership} plus {@code vmOrgIds}:
 * the organisations that workspace has live virtual machines in. A workspace
 * carries no organisation of its own, so this is derived, and it can name more
 * than one. The screen needs it because the account directory is not scoped by
 * organisation while the VM list is: without it, a link to a workspace outside
 * the reader's scope lands on an empty list that reads as "this person has no
 * virtual machines" rather than "not yours to see".
 */
public record AdminMembershipResponse(
        UUID workspaceId,
        String workspaceName,
        WorkspaceKind workspaceKind,
        WorkspaceMemberRole role,
        List<UUID> vmOrgIds) {
}
