package kr.ac.pusan.pickle.request;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkTarget;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.workspace.Workspace;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Actual request-kind admission and complete-source collection predicates. */
@Component
public class InstitutionRequestAccess {
    private final InstitutionAuthorization authorization;

    public InstitutionRequestAccess(InstitutionAuthorization authorization) { this.authorization = authorization; }

    /** The known request relationship survives workspace closure; creation and retry retain their separate live-state guards. */
    public boolean decide(AuthenticatedUser actor, ResourceType type, @Nullable Long institution, long workspace) {
        return check(actor, decisionWork(type), type, institution, workspace, true);
    }

    public boolean roster(AuthenticatedUser actor, @Nullable ResourceType type, @Nullable Long institution, long workspace) {
        return check(actor, rosterWork(type), type, institution, workspace, false);
    }

    public boolean retry(AuthenticatedUser actor, Request request) {
        return check(actor, retryWork(request.getResourceType()), request.getResourceType(), request.getOrgId(), request.getWorkspaceId(), true);
    }

    public boolean read(AuthenticatedUser actor, Request request) {
        ResourceType type = request.getResourceType();
        return check(actor, readWork(type), type, request.getOrgId(), request.getWorkspaceId(), true)
                || check(actor, supportWork(type), type, request.getOrgId(), request.getWorkspaceId(), true)
                || check(actor, decisionWork(type), type, request.getOrgId(), request.getWorkspaceId(), true)
                || check(actor, retryWork(type), type, request.getOrgId(), request.getWorkspaceId(), true);
    }

    /** A monetary decision is one complete assignment, including its budget work. */
    public boolean paidDecision(AuthenticatedUser actor, Request request, boolean roster) {
        if (request.getResourceType() != ResourceType.LLM_API_KEY) { return false; }
        if (actor.role() == UserRole.SYS_ADMIN) { return true; }
        var work = roster ? rosterWork(request.getResourceType()) : decisionWork(request.getResourceType());
        return work.flatMap(first -> target(request.getResourceType(), request.getOrgId(), request.getWorkspaceId(), false)
                .map(value -> authorization.sameSourceDecision(actor, first, InstitutionWork.LLM_KEY_BUDGET, value).allowed())).orElse(false);
    }

    /** Permission references for this stored request, never personal OWNER/ACL rights. */
    public List<String> allowedWorkIds(AuthenticatedUser actor, Request request) {
        List<String> result = new ArrayList<>();
        ResourceType type=request.getResourceType();
        for (var work : List.of(readWork(type),supportWork(type),decisionWork(type),retryWork(type))) {
            if (check(actor,work,type,request.getOrgId(),request.getWorkspaceId(),true)) { result.add(work.orElseThrow().id()); }
        }
        if (paidDecision(actor,request,false)) { result.add(InstitutionWork.LLM_KEY_BUDGET.id()); }
        return result.stream().distinct().sorted().toList();
    }

    public Specification<Request> readScope(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return (root, query, cb) -> cb.conjunction(); }
        return (root, query, cb) -> {
            List<Predicate> branches = new ArrayList<>();
            for (ResourceType type : ResourceType.values()) {
                for (Optional<InstitutionWork> candidate : List.of(readWork(type), supportWork(type), decisionWork(type), retryWork(type))) {
                    if (candidate.isEmpty()) { continue; }
                    for (var source : authorization.sources(actor, candidate.get())) {
                        var knownInstitutions = query.subquery(Long.class);
                        var knownInstitution = knownInstitutions.from(Org.class);
                        knownInstitutions.select(knownInstitution.get("id"));
                        Predicate branch = cb.and(cb.equal(root.get("resourceType"), type), root.get("orgId").in(knownInstitutions));
                        if (source.requiredInstitutionId() != null) {
                            var institutions = query.subquery(Long.class);
                            var institution = institutions.from(Org.class);
                            institutions.select(institution.get("id")).where(cb.equal(institution.get("publicId"), source.requiredInstitutionId()));
                            branch = cb.and(branch, root.get("orgId").in(institutions));
                        }
                        var workspaces = query.subquery(Long.class);
                        var workspace = workspaces.from(Workspace.class);
                        workspaces.select(workspace.get("id"));
                        if (source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.LISTED) {
                            workspaces.where(workspace.get("publicId").in(source.workspaceScope().workspaceIds()));
                        }
                        branches.add(cb.and(branch, root.get("workspaceId").in(workspaces)));
                    }
                }
            }
            return cb.or(branches.toArray(Predicate[]::new));
        };
    }

    private boolean check(AuthenticatedUser actor, Optional<InstitutionWork> work, @Nullable ResourceType type,
            @Nullable Long institution, long workspace, boolean historical) {
        if (work.isEmpty() || institution == null || type == null) { return false; }
        if (actor.role() == UserRole.SYS_ADMIN || (!work.get().write() && actor.role().isSysTier())) { return true; }
        return target(type, institution, workspace, historical)
                .map(value -> authorization.decision(actor, work.get(), value).allowed()).orElse(false);
    }

    private Optional<InstitutionWorkTarget> target(ResourceType type, @Nullable Long institution, long workspace, boolean historical) {
        return InstitutionAuthorization.group(type).flatMap(group -> historical
                ? authorization.historicalResourceTarget(group, institution, workspace)
                : authorization.resourceTarget(group, institution, workspace));
    }

    public static Optional<InstitutionWork> decisionWork(@Nullable ResourceType type) {
        return InstitutionAuthorization.requestDecisionWork(type);
    }

    public static Optional<InstitutionWork> rosterWork(@Nullable ResourceType type) {
        if (type == ResourceType.VM) { return Optional.of(InstitutionWork.VM_ROSTER_ISSUE); }
        if (type == ResourceType.LLM_API_KEY) { return Optional.of(InstitutionWork.LLM_ROSTER_ISSUE); }
        return Optional.empty();
    }

    private static Optional<InstitutionWork> retryWork(ResourceType type) {
        return type == ResourceType.VM ? Optional.of(InstitutionWork.VM_RECIPIENT_RETRY)
                : type == ResourceType.LLM_API_KEY ? Optional.of(InstitutionWork.LLM_RECIPIENT_RETRY) : Optional.empty();
    }

    private static Optional<InstitutionWork> readWork(ResourceType type) {
        return switch (type) {
            case VM -> Optional.of(InstitutionWork.VM_REQUEST_READ);
            case LLM_API_KEY -> Optional.of(InstitutionWork.LLM_REQUEST_READ);
            case DOMAIN -> Optional.of(InstitutionWork.DOMAIN_REQUEST_READ);
            case GPU -> Optional.of(InstitutionWork.GPU_REQUEST_READ);
        };
    }

    private static Optional<InstitutionWork> supportWork(ResourceType type) {
        return switch (type) {
            case VM -> Optional.of(InstitutionWork.VM_REQUEST_SUPPORT_READ);
            case LLM_API_KEY -> Optional.of(InstitutionWork.LLM_REQUEST_SUPPORT_READ);
            case DOMAIN -> Optional.of(InstitutionWork.DOMAIN_REQUEST_SUPPORT_READ);
            case GPU -> Optional.of(InstitutionWork.GPU_REQUEST_SUPPORT_READ);
        };
    }
}
