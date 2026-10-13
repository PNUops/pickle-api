package kr.ac.pusan.pickle.admin.bulk;

import kr.ac.pusan.pickle.access.dto.ActorAccessImpact;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessAction;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk change kind {@code ACCESS}: one person's grant on many resources of
 * one type, by an administrator.
 *
 * <p>This is a second way onto an access list. The first is the resource's
 * own owners and the owners of its workspace; support writes are available
 * globally to institution managers and administrators and system administrators.
     * New grants and role changes require an active member of the owning
     * workspace. Removing an existing personal grant also works after account
     * disable or membership removal, so a later activation cannot restore it.
     * Workspace-wide grants are untouched.
 *
 * <p>Every edit is an administrator's intervention in a workspace's own
 * list, so its audit row says so beside the shape the owner's edits leave.
 */
@Component
class AccessBulkChanges extends BulkChangeHandler<AccessBulkChanges.Target> {

    /**
     * One resource as the access machinery sees it. {@code listed} is false for a row the actor may have picked
     * from an admin listing but that has no access list at all.
     */
    record Target(ResourceIdentity identity, ResourceTypeAdapter adapter, boolean listed) {
    }

    private final Map<ResourceType, ResourceTypeAdapter> adapters;
    private final kr.ac.pusan.pickle.access.ResourceAccessGrantService grantChanges;
    private final kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock;
    private final DomainRepository domainRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final ObjectMapper objectMapper;

    AccessBulkChanges(kr.ac.pusan.pickle.access.ResourceAccessGrantService grantChanges,
            kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock, List<ResourceTypeAdapter> adapters,
            DomainRepository domainRepository, UserRepository userRepository,
            WorkspaceMemberRepository workspaceMemberRepository, ResourceAccessGrantRepository grantRepository,
            ObjectMapper objectMapper) {
        this.grantChanges = grantChanges; this.administrationLock = administrationLock;
        this.adapters = adapters.stream().collect(Collectors.toMap(ResourceTypeAdapter::type, Function.identity()));
        this.domainRepository = domainRepository; this.userRepository = userRepository;
        this.workspaceMemberRepository = workspaceMemberRepository; this.grantRepository = grantRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    Set<AdminBulkChangeKind> kinds() {
        return Set.of(AdminBulkChangeKind.ACCESS);
    }

    @Override
    void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors,
            Instant now) {
        AdminBulkAccessChange access = request.change().access();
        if (access.action() != AdminBulkAccessAction.REVOKE && access.role() == null) {
            errors.add(new FieldValidationError("change.access.role", "부여할 등급을 지정해 주세요."));
        }
    }

    @Override
    Map<UUID, Target> load(AdminBulkChangeRequest request) {
        ResourceType type = request.targetType().resourceType();
        ResourceTypeAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for resource type " + type);
        }
        Map<UUID, Target> targets = new LinkedHashMap<>();
        for (UUID id : request.targetIds()) {
            if (type == ResourceType.DOMAIN) {
                domainTarget(id, adapter).ifPresent(target -> targets.put(id, target));
                continue;
            }
            adapter.identifyByPublicId(id).ifPresent(identity -> targets.put(id,
                    new Target(identity, adapter, true)));
        }
        return targets;
    }

    /**
     * A domain as this change sees it. Only an external name has an access
     * list, but the admin domain listing shows every kind, so a platform
     * subdomain or a custom domain the actor can reach is loaded rather than
     * missed, and answered INELIGIBLE with its name: it was in front of the
     * administrator, and pretending it does not exist would say something
     * false. A REMOVED row is missing, as it is to every other domain change;
     * out of the actor's scope, reach answers NOT_FOUND before this matters.
     */
    private Optional<Target> domainTarget(UUID id, ResourceTypeAdapter adapter) {
        Domain domain = domainRepository.findByPublicId(id).orElse(null);
        if (domain == null || domain.getStatus() == DomainStatus.REMOVED) {
            return Optional.empty();
        }
        if (domain.getKind() != DomainKind.EXTERNAL) {
            ResourceIdentity identity = new ResourceIdentity(domain.getId(), domain.getPublicId(),
                    domain.getWorkspaceId(), domain.getFqdn(), null, domain.getStatus().name());
            return Optional.of(new Target(identity, adapter, false));
        }
        return adapter.identifyByPublicId(id)
                .map(identity -> new Target(identity, adapter, true));
    }

    @Override
    long orderKey(Target target) {
        return target.identity().id();
    }

    @Override
    String name(Target target) {
        return target.identity().name();
    }

    @Override
    @Nullable AdminBulkChangeReason accessRefusal(AuthenticatedUser actor, Target target,
            AdminBulkChangeSpec change) {
        return kr.ac.pusan.pickle.admin.StudentSupportPolicy.canWriteWorkspaceAccess(actor)
                ? null : AdminBulkChangeReason.FORBIDDEN;
    }

    @Override
    Map<String, Object> fingerprintValues(Target target, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!target.listed()) {
            // Nothing about an unlisted row can change: the fingerprint only
            // has to say so.
            values.put("listed", false);
            return values;
        }
        User grantee = eligibleUser(change.access());
        values.put("granteeRole", grantee == null ? null
                : grantOf(target, grantee).map(grant -> grant.getRole().name()).orElse(null));
        return values;
    }

    @Override
    Map<String, Object> observationValues(Target target, AdminBulkChangeSpec change) {
        return publicValues(fingerprintValues(target, change), Set.of("listed", "granteeRole"));
    }

    @Override
    Judgement judge(AuthenticatedUser actor, Target target, AdminBulkChangeSpec change,
            Instant now) {
        if (!target.listed()) {
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        AdminBulkAccessChange access = change.access();
        User grantee = eligibleUser(access);
        if (grantee == null) {
            // An id no active account has is refused as ineligible, not as
            // missing: the grantee's existence is not this surface's to disclose.
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        boolean member = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(target.identity().workspaceId(), grantee.getId())
                .isPresent();
        if (!member && access.action() != AdminBulkAccessAction.REVOKE) {
            return Judgement.refused(AdminBulkChangeReason.NOT_MEMBER);
        }
        ResourceAccessGrant grant = grantOf(target, grantee).orElse(null);
        ResourceRole current = grant == null ? null : grant.getRole();
        return switch (access.action()) {
            case GRANT -> {
                if (grant != null) {
                    yield current == access.role() ? Judgement.unchanged()
                            : Judgement.refused(AdminBulkChangeReason.ALREADY_GRANTED);
                }
                yield Judgement.change(List.of(diff(objectMapper, "role", null, access.role())),
                        grantee.getId());
            }
            case CHANGE -> {
                if (grant == null) {
                    yield Judgement.refused(AdminBulkChangeReason.NO_GRANT);
                }
                if (current == access.role()) {
                    yield Judgement.unchanged();
                }
                yield Judgement.change(List.of(diff(objectMapper, "role", current, access.role())),
                        grantee.getId());
            }
            case REVOKE -> {
                if (grant == null) {
                    yield Judgement.refused(AdminBulkChangeReason.NO_GRANT);
                }
                yield Judgement.change(List.of(diff(objectMapper, "role", current, null)),
                        grantee.getId());
            }
        };
    }

    @Override
    void prepareApply(AuthenticatedUser actor, AdminBulkChangeRequest request) { administrationLock.acquire(); }

    @Override
    @Nullable ActorAccessImpact actorAccessImpact(AuthenticatedUser actor, Target target,
            AdminBulkChangeSpec change) {
        if (!target.listed()) { return null; }
        User grantee = eligibleUser(change.access());
        if (grantee == null) { return null; }
        var access = change.access();
        return grantChanges.bulkImpact(actor, target.adapter().type(), target.identity().publicId(), grantee.getId(),
                access.action().name(), access.role());
    }

    @Override
    Map<String, Object> fingerprintValues(AuthenticatedUser actor, Target target, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>(fingerprintValues(target, change));
        var impact = actorAccessImpact(actor, target, change);
        if (impact != null) { values.put("actorAccessFingerprint", impact.fingerprint()); }
        User grantee = eligibleUser(change.access());
        values.put("eligibleStatus", grantee == null ? null : grantee.getStatus().name());
        values.put("member", grantee != null && workspaceMemberRepository.findByWorkspaceIdAndUserId(target.identity().workspaceId(), grantee.getId()).isPresent());
        return values;
    }

    @Override
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, Target target, Judgement judgement,
            AdminBulkChangeSpec change, UUID batchId, String ip) {
        return write(actor, target, judgement, change, batchId, ip, null);
    }

    @Override
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, Target target, Judgement judgement,
            AdminBulkChangeSpec change, UUID batchId, String ip,
            kr.ac.pusan.pickle.access.dto.AccessConfirmation confirmation) {
        var access = change.access();
        boolean changed = grantChanges.bulkChange(actor, target.adapter().type(), target.identity().publicId(),
                (Long) judgement.plan(), access.action().name(), access.role(), batchId, ip, confirmation);
        return changed ? null : access.action() == AdminBulkAccessAction.GRANT
                ? AdminBulkChangeReason.ALREADY_GRANTED : AdminBulkChangeReason.NO_GRANT;
    }

    private @Nullable User eligibleUser(AdminBulkAccessChange access) {
        return userRepository.findByPublicId(access.userId())
                .filter(user -> access.action() == AdminBulkAccessAction.REVOKE || user.getStatus() == UserStatus.ACTIVE)
                .orElse(null);
    }

    private Optional<ResourceAccessGrant> grantOf(Target target, User grantee) {
        return grantRepository.findByResourceTypeAndResourceIdAndUserId(
                target.adapter().type(), target.identity().id(), grantee.getId());
    }
}
