package kr.ac.pusan.pickle.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.dto.AccessConfirmation;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact.AccessIncrease;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact.PendingResourceOwner;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Pure projections over the existing membership and grant model, never domain writes. */
@Service
public class ActorAccessImpacts {
    private final ResourceAccessGrantRepository grants;
    private final WorkspaceMemberRepository members;
    private final List<ResourceTypeAdapter> adapters;

    public ActorAccessImpacts(ResourceAccessGrantRepository grants, WorkspaceMemberRepository members,
            List<ResourceTypeAdapter> adapters) {
        this.grants = grants; this.members = members; this.adapters = adapters;
    }

    public ActorAccessImpact grant(AuthenticatedUser actor, ResourceType type, ResourceIdentity resource,
            UUID workspaceId, String action, @Nullable UUID grantId, AccessGranteeType granteeType,
            @Nullable Long userId, @Nullable ResourceRole role) {
        boolean member = members.findByWorkspaceIdAndUserId(resource.workspaceId(), actor.id()).isPresent();
        List<ResourceAccessGrant> existing = grants.findByResourceTypeAndResourceIdOrderByIdAsc(type, resource.id());
        ResourceRole before = effective(existing, actor.id(), member, null, null, null, null);
        ResourceRole after = effective(existing, actor.id(), member, grantId, action, granteeType,
                userId == null || !userId.equals(actor.id()) ? null : role);
        if ("ADD".equals(action) && granteeType == AccessGranteeType.WORKSPACE && member) {
            after = higher(after, role);
        }
        if ("UPDATE".equals(action) && granteeType == AccessGranteeType.WORKSPACE && member) {
            after = higher(after, role);
        }
        List<AccessIncrease> gains = after != null && (before == null || after.ordinal() < before.ordinal())
                ? List.of(new AccessIncrease(type, resource.publicId(), workspaceId,
                        before == null ? null : before.name(), after.name())) : List.of();
        String binding = actor.id() + ":" + type + ":" + resource.publicId() + ":" + action + ":"
                + grantId + ":" + granteeType + ":" + userId + ":" + role + ":" + member + ":" + snapshot(existing);
        return bind(actor, binding, gains, List.of());
    }

    public ActorAccessImpact membership(AuthenticatedUser actor, Workspace workspace,
            WorkspaceMemberRole next, String binding, List<PendingResourceOwner> pending) {
        WorkspaceMemberRole before = members.findByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .map(kr.ac.pusan.pickle.workspace.WorkspaceMember::getRole).orElse(null);
        List<AccessIncrease> gains = new ArrayList<>();
        if (before == null || before != WorkspaceMemberRole.OWNER && next == WorkspaceMemberRole.OWNER) {
            gains.add(new AccessIncrease(null, workspace.getPublicId(), workspace.getPublicId(),
                    before == null ? null : before.name(), next.name()));
        }
        StringBuilder state = new StringBuilder(binding).append(':').append(actor.id()).append(':').append(before);
        for (ResourceTypeAdapter adapter : adapters.stream().sorted(Comparator.comparing(a -> a.type().name())).toList()) {
            for (long id : adapter.idsOwnedByWorkspace(workspace.getId()).stream().sorted().toList()) {
                ResourceIdentity resource = adapter.identify(id).orElse(null);
                if (resource == null) { continue; }
                List<ResourceAccessGrant> listed = grants.findByResourceTypeAndResourceIdOrderByIdAsc(adapter.type(), id);
                state.append(':').append(adapter.type()).append(':').append(id).append(':').append(snapshot(listed));
                ResourceRole current = effective(listed, actor.id(), before != null, null, null, null, null);
                ResourceRole projected = effective(listed, actor.id(), true, null, null, null, null);
                if (projected != null && (current == null || projected.ordinal() < current.ordinal())) {
                    gains.add(new AccessIncrease(adapter.type(), resource.publicId(), workspace.getPublicId(),
                            current == null ? null : current.name(), projected.name()));
                }
            }
        }
        return bind(actor, state.toString(), gains, pending);
    }

    public ActorAccessImpact bind(AuthenticatedUser actor, String binding, List<AccessIncrease> gains, List<PendingResourceOwner> pending) {
        try {
            String token = authorityContext(actor) + ':' + binding + ':' + gains + ':' + pending;
            return new ActorAccessImpact(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8))), List.copyOf(gains), List.copyOf(pending));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String authorityContext(AuthenticatedUser actor) {
        return actor.publicId() + ":" + actor.role() + ":" + actor.institutionContext().state() + ":"
                + actor.institutionContext().authority().map(kr.ac.pusan.pickle.security.institution.InstitutionAuthority::fingerprint)
                    .orElseGet(() -> actor.institutionContext().failure().map(Enum::name).orElse("INVALID"));
    }

    public static void requireConfirmed(ActorAccessImpact impact, @Nullable AccessConfirmation confirmation) {
        if (!impact.requiresConfirmation()) { return; }
        if (confirmation == null || !confirmation.confirmed() || confirmation.purpose() == null
                || confirmation.purpose().isBlank() || confirmation.purpose().length() > 200
                || !impact.fingerprint().equals(confirmation.fingerprint())) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("confirmation",
                    "변경 목적과 현재 미리보기의 정확한 대상·얻는 권한을 확인해 주세요.")));
        }
    }

    public static Map<String, Object> auditDetail(ActorAccessImpact impact, @Nullable AccessConfirmation confirmation) {
        if (!impact.requiresConfirmation() || confirmation == null) { return Map.of(); }
        return Map.of("purpose", confirmation.purpose().strip(), "accessIncreases", impact.increases(),
                "pendingResourceOwners", impact.pendingResourceOwners(), "confirmationFingerprint", impact.fingerprint());
    }

    private static String snapshot(List<ResourceAccessGrant> grants) {
        return grants.stream().map(g -> g.getPublicId() + ":" + g.getGranteeType() + ":" + g.getUserId() + ":" + g.getRole())
                .sorted().toList().toString();
    }

    private static @Nullable ResourceRole effective(List<ResourceAccessGrant> listed, long actorId, boolean member,
            @Nullable UUID changedId, @Nullable String action, @Nullable AccessGranteeType type, @Nullable ResourceRole newRole) {
        if (!member) { return null; }
        ResourceRole best = null;
        for (ResourceAccessGrant grant : listed) {
            if (changedId != null && changedId.equals(grant.getPublicId())) { continue; }
            if (grant.getGranteeType() == AccessGranteeType.WORKSPACE || Long.valueOf(actorId).equals(grant.getUserId())) {
                best = higher(best, grant.getRole());
            }
        }
        if (("ADD".equals(action) || "UPDATE".equals(action)) && newRole != null) { best = higher(best, newRole); }
        return best;
    }
    private static @Nullable ResourceRole higher(@Nullable ResourceRole left, @Nullable ResourceRole right) {
        return right == null || left != null && left.atLeast(right) ? left : right;
    }
}
