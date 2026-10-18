package kr.ac.pusan.pickle.security.institution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Institution work only. Each caller retains its operation's explicit existing SYS subset. */
@Component
public class InstitutionAuthorization {
    private final JdbcTemplate jdbc;

    public InstitutionAuthorization(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<InstitutionAuthority> authority(AuthenticatedUser actor) {
        if (!actor.role().isOrgTier() || actor.institutionContext().state() == InstitutionActorContext.State.INVALID) {
            return Optional.empty();
        }
        return actor.institutionContext().authority();
    }

    public InstitutionWorkDecision decision(AuthenticatedUser actor, InstitutionWork work, InstitutionWorkTarget target) {
        return authority(actor).map(authority -> authority.evaluate(work, target))
                .orElseGet(() -> new InstitutionWorkDecision(List.of()));
    }

    public InstitutionWorkDecision sameSourceDecision(AuthenticatedUser actor, InstitutionWork first,
            InstitutionWork second, InstitutionWorkTarget target) {
        InstitutionWorkDecision firstDecision = decision(actor, first, target);
        InstitutionWorkDecision secondDecision = decision(actor, second, target);
        return new InstitutionWorkDecision(firstDecision.sources().stream().filter(secondDecision.sources()::contains).toList());
    }

    public List<InstitutionAuthorizationSource> sources(AuthenticatedUser actor, InstitutionWork work) {
        return authority(actor).map(authority -> authority.assignments().stream().filter(assignment -> assignment.includes(work))
                .map(assignment -> source(assignment, work)).filter(source -> source.workspaceScope() == null
                        || source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.ALL
                        || !source.workspaceScope().workspaceIds().isEmpty()).toList()).orElseGet(List::of);
    }

    /** Actual server-read object relations only; this does not establish that an object owns these IDs. */
    public Optional<InstitutionWorkTarget> resourceTarget(InstitutionWorkGroup group,
            @Nullable Long actualOrgId, @Nullable Long workspaceId) {
        return resourceTarget(group, actualOrgId, workspaceId, false);
    }

    /** Fixed historical read bindings may retain stored relations to closed workspaces. */
    public Optional<InstitutionWorkTarget> historicalResourceTarget(InstitutionWorkGroup group,
            @Nullable Long actualOrgId, @Nullable Long workspaceId) {
        return resourceTarget(group, actualOrgId, workspaceId, true);
    }

    public Optional<InstitutionWorkTarget> institutionTarget(long actualOrgId) {
        return orgPublicId(actualOrgId).map(InstitutionWorkTarget::institution);
    }

    public Optional<InstitutionWorkTarget> workspaceTarget(long workspaceId) {
        return workspacePublicId(workspaceId, false).map(InstitutionWorkTarget::workspace);
    }

    public Optional<InstitutionWorkTarget> historicalWorkspaceTarget(long workspaceId) {
        return workspacePublicId(workspaceId, true).map(InstitutionWorkTarget::workspace);
    }

    public Optional<InstitutionWorkTarget> requestDecisionTarget(ResourceType type, long orgId, long workspaceId) {
        return group(type).flatMap(group -> resourceTarget(group, orgId, workspaceId));
    }

    public static Optional<InstitutionWork> requestDecisionWork(@Nullable ResourceType type) {
        if (type == null) { return Optional.empty(); }
        return Optional.of(switch (type) {
            case VM -> InstitutionWork.VM_REQUEST_DECIDE;
            case LLM_API_KEY -> InstitutionWork.LLM_REQUEST_DECIDE;
            case DOMAIN -> InstitutionWork.DOMAIN_REQUEST_DECIDE;
            case GPU -> InstitutionWork.GPU_REQUEST_DECIDE;
        });
    }

    public static Optional<InstitutionWorkGroup> group(@Nullable ResourceType type) {
        if (type == null) { return Optional.empty(); }
        return Optional.of(switch (type) {
            case VM -> InstitutionWorkGroup.VM;
            case LLM_API_KEY -> InstitutionWorkGroup.LLM;
            case DOMAIN -> InstitutionWorkGroup.DOMAIN;
            case GPU -> InstitutionWorkGroup.GPU;
        });
    }

    private InstitutionAuthorizationSource source(InstitutionAssignment assignment, InstitutionWork work) {
        boolean globalRead = assignment.mode() == InstitutionAssignment.Mode.FULL
                && InstitutionWorkCatalogue.isGlobalResourceReadInFull(work);
        UUID requiredOrg = (work.boundary() == InstitutionWork.Boundary.RESOURCE && !globalRead
                || work.boundary() == InstitutionWork.Boundary.INSTITUTION) ? assignment.institutionId() : null;
        InstitutionWorkspaceScope scope = work.group() == null ? null : assignment.mode() == InstitutionAssignment.Mode.FULL
                ? InstitutionWorkspaceScope.all() : assignment.scopes().get(work.group());
        return new InstitutionAuthorizationSource(new InstitutionWorkDecision.GrantSource(assignment.accountId(),
                assignment.institutionId(), assignment.mode(), assignment.revision()), work, work.boundary(), work.group(), requiredOrg, scope, globalRead);
    }

    private Optional<InstitutionWorkTarget> resourceTarget(InstitutionWorkGroup group,
            @Nullable Long orgId, @Nullable Long workspaceId, boolean historical) {
        Objects.requireNonNull(group);
        if (group == InstitutionWorkGroup.WORKSPACE || workspaceId == null) { return Optional.empty(); }
        UUID institution = orgId == null ? null : orgPublicId(orgId).orElse(null);
        if (orgId != null && institution == null) { return Optional.empty(); }
        return workspacePublicId(workspaceId, historical).map(workspace -> InstitutionWorkTarget.resource(institution, group, workspace));
    }

    private Optional<UUID> orgPublicId(long orgId) {
        return jdbc.query("select public_id from orgs where id=?", (rs, row) -> rs.getObject(1, UUID.class), orgId).stream().findFirst();
    }

    private Optional<UUID> workspacePublicId(long workspaceId, boolean historical) {
        return jdbc.query("select public_id from workspaces where id=?" + (historical ? "" : " and deleted_at is null"),
                (rs, row) -> rs.getObject(1, UUID.class), workspaceId).stream().findFirst();
    }
}
