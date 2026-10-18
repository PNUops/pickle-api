package kr.ac.pusan.pickle.admin;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorizationSource;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import kr.ac.pusan.pickle.workspace.Workspace;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Fixed key projections and mutation admission; personal key ACL paths remain separate. */
@Component
public class InstitutionLlmAccess {
    public enum InstitutionLlmProjection { METADATA, OBSERVATION, COST, COMMAND, ACL }

    private final InstitutionAuthorization authorization;
    private final OrgAdministrationLock lock;
    private final LifecycleActors actors;

    public InstitutionLlmAccess(InstitutionAuthorization authorization, OrgAdministrationLock lock, LifecycleActors actors) {
        this.authorization = authorization; this.lock = lock; this.actors = actors;
    }

    public AuthenticatedUser currentForWrite(AuthenticatedUser actor) {
        lock.acquire();
        return actors.currentAfterAdministrationLock(actor);
    }

    public AuthenticatedUser currentForRead(AuthenticatedUser actor) { return actors.current(actor); }

    public boolean collectionReader(AuthenticatedUser actor) {
        return systemRead(actor) || java.util.stream.Stream.of(InstitutionWork.LLM_METADATA_READ, InstitutionWork.LLM_METADATA_SUPPORT_READ,
                InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_OBSERVE_SUPPORT_READ, InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET, InstitutionWork.LLM_STATE_OPERATE, InstitutionWork.LLM_PERIOD, InstitutionWork.LLM_REVOKE,
                InstitutionWork.LLM_ACL_SUPPORT_READ, InstitutionWork.LLM_ACL_SUPPORT_MANAGE)
                .anyMatch(work -> !authorization.sources(actor, work).isEmpty());
    }

    public AuthenticatedUser currentAfterLock(AuthenticatedUser actor) {
        return actors.currentAfterAdministrationLock(actor);
    }

    public boolean metadata(AuthenticatedUser actor, LlmApiKey key) {
        return systemRead(actor) || institution(actor, InstitutionWork.LLM_METADATA_READ, key, true)
                || institution(actor, InstitutionWork.LLM_METADATA_SUPPORT_READ, key, true);
    }

    public boolean observation(AuthenticatedUser actor, LlmApiKey key) {
        return systemRead(actor) || institution(actor, InstitutionWork.LLM_OBSERVE, key, true)
                || institution(actor, InstitutionWork.LLM_OBSERVE_SUPPORT_READ, key, true);
    }

    public boolean cost(AuthenticatedUser actor, LlmApiKey key) {
        return systemRead(actor) || institution(actor, InstitutionWork.LLM_KEY_COST_READ, key, true);
    }

    public boolean selectable(AuthenticatedUser actor, LlmApiKey key) {
        return metadata(actor, key) || observation(actor, key) || cost(actor, key);
    }

    public Specification<LlmApiKey> selectorScope(AuthenticatedUser actor) {
        return readScope(actor, InstitutionLlmProjection.METADATA).or(readScope(actor, InstitutionLlmProjection.OBSERVATION))
                .or(readScope(actor, InstitutionLlmProjection.COST)).or(readScope(actor, InstitutionLlmProjection.COMMAND))
                .or(readScope(actor, InstitutionLlmProjection.ACL));
    }

    public boolean mutation(AuthenticatedUser actor, InstitutionWork work, LlmApiKey key) {
        if (!List.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_STATE_OPERATE, InstitutionWork.LLM_PERIOD,
                InstitutionWork.LLM_REVOKE, InstitutionWork.LLM_KEY_BUDGET).contains(work)) { return false; }
        if (actor.role() == UserRole.SYS_ADMIN) { return true; }
        if (actor.role() == UserRole.SYS_MANAGER) {
            return work == InstitutionWork.LLM_TOKEN_OPERATE || work == InstitutionWork.LLM_STATE_OPERATE || work == InstitutionWork.LLM_PERIOD;
        }
        return institution(actor, work, key, false);
    }

    public boolean aclReference(AuthenticatedUser actor, LlmApiKey key) {
        return systemRead(actor) || institution(actor, InstitutionWork.LLM_ACL_SUPPORT_READ, key, true)
                || institution(actor, InstitutionWork.LLM_ACL_SUPPORT_MANAGE, key, true);
    }

    /** Current permission on this actual key; existing business-state checks remain separate. */
    public List<String> allowedWorkIds(AuthenticatedUser actor, LlmApiKey key) {
        List<InstitutionWork> reads = List.of(InstitutionWork.LLM_METADATA_READ, InstitutionWork.LLM_METADATA_SUPPORT_READ,
                InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_OBSERVE_SUPPORT_READ, InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWork.LLM_ACL_SUPPORT_READ);
        List<String> result = new ArrayList<>();
        reads.stream().filter(work -> systemRead(actor) || institution(actor, work, key, true)).map(InstitutionWork::id).forEach(result::add);
        if (actor.role() == UserRole.SYS_ADMIN || institution(actor, InstitutionWork.LLM_ACL_SUPPORT_MANAGE, key, true)) {
            result.add(InstitutionWork.LLM_ACL_SUPPORT_MANAGE.id());
        }
        List.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_STATE_OPERATE, InstitutionWork.LLM_PERIOD,
                InstitutionWork.LLM_REVOKE, InstitutionWork.LLM_KEY_BUDGET).stream().filter(work -> mutation(actor, work, key))
                .map(InstitutionWork::id).forEach(result::add);
        return result.stream().distinct().sorted().toList();
    }

    public Specification<LlmApiKey> readScope(AuthenticatedUser actor, InstitutionLlmProjection projection) {
        if (systemRead(actor)) { return (root, query, cb) -> cb.conjunction(); }
        List<InstitutionAuthorizationSource> sources = new ArrayList<>();
        switch (projection) {
            case METADATA -> {
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_METADATA_READ));
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_METADATA_SUPPORT_READ));
            }
            case OBSERVATION -> {
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_OBSERVE));
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_OBSERVE_SUPPORT_READ));
            }
            case COST -> sources.addAll(authorization.sources(actor, InstitutionWork.LLM_KEY_COST_READ));
            case ACL -> {
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_ACL_SUPPORT_READ));
                sources.addAll(authorization.sources(actor, InstitutionWork.LLM_ACL_SUPPORT_MANAGE));
            }
            case COMMAND -> {
                for (var work : List.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET, InstitutionWork.LLM_STATE_OPERATE,
                        InstitutionWork.LLM_PERIOD, InstitutionWork.LLM_REVOKE)) { sources.addAll(authorization.sources(actor, work)); }
            }
        }
        return (root, query, cb) -> {
            List<Predicate> branches = new ArrayList<>();
            for (InstitutionAuthorizationSource source : sources) {
                var knownInstitutions = query.subquery(Long.class);
                var knownInstitution = knownInstitutions.from(Org.class);
                knownInstitutions.select(knownInstitution.get("id"));
                Predicate branch = cb.or(cb.isNull(root.get("orgId")), root.get("orgId").in(knownInstitutions));
                if (source.requiredInstitutionId() != null) {
                    var institutions = query.subquery(Long.class);
                    var institution = institutions.from(Org.class);
                    institutions.select(institution.get("id")).where(cb.equal(institution.get("publicId"), source.requiredInstitutionId()));
                    branch = cb.and(branch, root.get("orgId").in(institutions));
                }
                {
                    var workspaces = query.subquery(Long.class);
                    var workspace = workspaces.from(Workspace.class);
                    workspaces.select(workspace.get("id"));
                    Predicate workspacePredicate = cb.conjunction();
                    if (source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.LISTED) {
                        workspacePredicate = cb.and(workspacePredicate, workspace.get("publicId").in(source.workspaceScope().workspaceIds()));
                    }
                    if (projection == InstitutionLlmProjection.COMMAND) { workspacePredicate = cb.and(workspacePredicate, cb.isNull(workspace.get("deletedAt"))); }
                    workspaces.where(workspacePredicate);
                    branch = cb.and(branch, root.get("workspaceId").in(workspaces));
                }
                branches.add(branch);
            }
            return cb.or(branches.toArray(Predicate[]::new));
        };
    }

    private boolean institution(AuthenticatedUser actor, InstitutionWork work, LlmApiKey key, boolean historical) {
        var target = historical ? authorization.historicalResourceTarget(InstitutionWorkGroup.LLM, key.getOrgId(), key.getWorkspaceId())
                : authorization.resourceTarget(InstitutionWorkGroup.LLM, key.getOrgId(), key.getWorkspaceId());
        return target.map(value -> authorization.decision(actor, work, value).allowed()).orElse(false);
    }

    public static boolean systemRead(AuthenticatedUser actor) {
        return actor.role() == UserRole.SYS_VIEWER || actor.role() == UserRole.SYS_MANAGER || actor.role() == UserRole.SYS_ADMIN;
    }
}
