package kr.ac.pusan.pickle.admin.bulk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.GrantChange;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessAction;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.gpu.GpuAllocation;
import kr.ac.pusan.pickle.gpu.GpuStore;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.resource.ResourceIdentity;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk change kind {@code ACCESS}: one person's grant on many resources of
 * one type, by an administrator.
 *
 * <p>This is a second way onto an access list. The first is the resource's
 * own owners and the owners of its workspace; this one is a system
 * administrator, or an organisation administrator of the institution the
 * resource belongs to, the same tier that may schedule a VM's deletion.
 * Every rule of the first way holds here: the person must be active and a
 * member of the owning workspace, only grants naming a person are made, and
 * one person holds one entry per resource.
 *
 * <p>Every edit is an administrator's intervention in a workspace's own
 * list, so its audit row says so beside the shape the owner's edits leave.
 */
@Component
class AccessBulkChanges extends BulkChangeHandler<AccessBulkChanges.Target> {

    /** One resource as the access machinery sees it, plus the institution it belongs to. */
    record Target(ResourceIdentity identity, ResourceTypeAdapter adapter, @Nullable Long orgId) {
    }

    private final Map<ResourceType, ResourceTypeAdapter> adapters;
    private final VmRepository vmRepository;
    private final LlmApiKeyRepository keyRepository;
    private final DomainRepository domainRepository;
    private final GpuStore gpuStore;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final ResourceAccessResolver resolver;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final ObjectMapper objectMapper;

    AccessBulkChanges(List<ResourceTypeAdapter> adapters, VmRepository vmRepository,
            LlmApiKeyRepository keyRepository, DomainRepository domainRepository,
            GpuStore gpuStore, UserRepository userRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, ResourceAccessResolver resolver,
            JdbcTemplate jdbcTemplate, AuditService auditService, AuditIds auditIds,
            ObjectMapper objectMapper) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(ResourceTypeAdapter::type, Function.identity()));
        this.vmRepository = vmRepository;
        this.keyRepository = keyRepository;
        this.domainRepository = domainRepository;
        this.gpuStore = gpuStore;
        this.userRepository = userRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
        this.resolver = resolver;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.objectMapper = objectMapper;
    }

    @Override
    Set<AdminBulkChangeKind> kinds() {
        return Set.of(AdminBulkChangeKind.ACCESS);
    }

    @Override
    void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors) {
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
            adapter.identifyByPublicId(id).ifPresent(identity -> targets.put(id,
                    new Target(identity, adapter, orgIdOf(type, identity.id()).orElse(null))));
        }
        return targets;
    }

    /**
     * The institution a resource belongs to, which the access machinery does
     * not carry because grants are decided by workspace, not by institution.
     * The administrator's reach is decided by institution, so it is read
     * here, from each type's own row.
     */
    private Optional<Long> orgIdOf(ResourceType type, long resourceId) {
        return switch (type) {
            case VM -> vmRepository.findById(resourceId).map(Vm::getOrgId);
            case LLM_API_KEY -> keyRepository.findById(resourceId).map(LlmApiKey::getOrgId);
            case DOMAIN -> domainRepository.findById(resourceId).map(Domain::getOrgId);
            case GPU -> gpuStore.allocation(resourceId).map(GpuAllocation::orgId);
        };
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
        if (actor.role() == UserRole.SYS_ADMIN) {
            return null;
        }
        if (actor.role().isOrgTier()) {
            if (target.orgId() == null || !actor.reads(target.orgId())) {
                return AdminBulkChangeReason.NOT_FOUND;
            }
            return actor.administers(target.orgId()) ? null : AdminBulkChangeReason.FORBIDDEN;
        }
        return AdminBulkChangeReason.FORBIDDEN;
    }

    @Override
    Map<String, Object> fingerprintValues(Target target, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>();
        User grantee = activeUser(change.access().userId());
        values.put("granteeRole", grantee == null ? null
                : grantOf(target, grantee).map(grant -> grant.getRole().name()).orElse(null));
        return values;
    }

    @Override
    Judgement judge(AuthenticatedUser actor, Target target, AdminBulkChangeSpec change,
            Instant now) {
        AdminBulkAccessChange access = change.access();
        User grantee = activeUser(access.userId());
        if (grantee == null) {
            // An id no active account has is refused as ineligible, not as
            // missing: the grantee's existence is not this surface's to disclose.
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        boolean member = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(target.identity().workspaceId(), grantee.getId())
                .isPresent();
        if (!member) {
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
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, Target target,
            Judgement judgement, AdminBulkChangeSpec change, UUID batchId, String ip) {
        AdminBulkAccessChange access = change.access();
        long userId = (Long) judgement.plan();
        ResourceType type = target.adapter().type();
        long resourceId = target.identity().id();
        // Read before the write, for the same reason the owner path does: the
        // self-edit marker needs both sides of the comparison.
        ResourceStanding standingBefore = userId == actor.id()
                ? resolver.standing(type, resourceId, target.identity().workspaceId(), actor.id())
                : null;
        switch (access.action()) {
            case GRANT -> {
                // One statement that either inserts or says the entry exists,
                // so a grant that raced in from the owner's side is this
                // item's answer and not a constraint violation that would
                // poison the batch's transaction.
                List<Long> inserted = jdbcTemplate.queryForList("""
                        insert into resource_access_grants
                               (resource_type, resource_id, grantee_type, user_id, role)
                        values (?::resource_type, ?, 'USER', ?, ?::resource_role)
                        on conflict (resource_type, resource_id, user_id)
                            where grantee_type = 'USER' do nothing
                        returning id
                        """, Long.class, type.name(), resourceId, userId, access.role().name());
                if (inserted.isEmpty()) {
                    return AdminBulkChangeReason.ALREADY_GRANTED;
                }
                ResourceAccessGrant saved = grantRepository.findById(inserted.getFirst())
                        .orElseThrow();
                audit(actor, target, GrantChange.ADD, saved, null, batchId, ip);
                breakGlassIfSelf(actor, target, standingBefore, batchId, ip, saved, null);
            }
            case CHANGE -> {
                ResourceAccessGrant grant = grantRepository
                        .findByResourceTypeAndResourceIdAndUserId(type, resourceId, userId)
                        .orElse(null);
                if (grant == null) {
                    return AdminBulkChangeReason.NO_GRANT;
                }
                ResourceRole previous = grant.getRole();
                grant.setRole(access.role());
                audit(actor, target, GrantChange.UPDATE, grant, previous, batchId, ip);
                breakGlassIfSelf(actor, target, standingBefore, batchId, ip, grant, previous);
            }
            case REVOKE -> {
                ResourceAccessGrant grant = grantRepository
                        .findByResourceTypeAndResourceIdAndUserId(type, resourceId, userId)
                        .orElse(null);
                if (grant == null) {
                    return AdminBulkChangeReason.NO_GRANT;
                }
                grantRepository.delete(grant);
                audit(actor, target, GrantChange.REMOVE, grant, null, batchId, ip);
            }
        }
        return null;
    }

    /**
     * The owner path's record, with two more facts: which batch this was part
     * of, and that an administrator rather than an owner made the edit. The
     * shape stays the owner path's so a reader of the trail meets one shape
     * per action name.
     */
    private void audit(AuthenticatedUser actor, Target target, GrantChange change,
            ResourceAccessGrant grant, @Nullable ResourceRole previousRole, UUID batchId,
            String ip) {
        ResourceAccessAudit names = target.adapter().accessAudit();
        auditService.recordAfterCommit(actor.id(), actor.role().name(), names.actionOf(change),
                names.targetType(), target.identity().publicId(),
                detailOf(grant, previousRole, batchId), ip);
    }

    private Map<String, Object> detailOf(ResourceAccessGrant grant,
            @Nullable ResourceRole previousRole, UUID batchId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grantId", grant.getPublicId());
        detail.put("granteeType", grant.getGranteeType().name());
        detail.put("granteeUserId", auditIds.user(grant.getUserId()));
        detail.put("role", grant.getRole().name());
        if (previousRole != null) {
            detail.put("previousRole", previousRole.name());
        }
        detail.put("adminIntervention", true);
        detail.put("batchId", batchId);
        return detail;
    }

    /**
     * An administrator naming themselves is the case the owner path records
     * as break-glass, and it is recorded here by the same rule: only when the
     * edit is what puts them inside a resource they could not reach before.
     */
    private void breakGlassIfSelf(AuthenticatedUser actor, Target target,
            @Nullable ResourceStanding before, UUID batchId, String ip, ResourceAccessGrant grant,
            @Nullable ResourceRole previousRole) {
        if (before == null || before.atLeast(ResourceRole.MEMBER)) {
            return;
        }
        ResourceStanding after = resolver.standing(target.adapter().type(),
                target.identity().id(), target.identity().workspaceId(), actor.id());
        if (!after.atLeast(ResourceRole.MEMBER)) {
            return;
        }
        ResourceAccessAudit names = target.adapter().accessAudit();
        auditService.recordAfterCommit(actor.id(), actor.role().name(), names.breakGlass(),
                names.targetType(), target.identity().publicId(),
                detailOf(grant, previousRole, batchId), ip);
    }

    private @Nullable User activeUser(UUID publicId) {
        return userRepository.findByPublicId(publicId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElse(null);
    }

    private Optional<ResourceAccessGrant> grantOf(Target target, User grantee) {
        return grantRepository.findByResourceTypeAndResourceIdAndUserId(
                target.adapter().type(), target.identity().id(), grantee.getId());
    }
}
