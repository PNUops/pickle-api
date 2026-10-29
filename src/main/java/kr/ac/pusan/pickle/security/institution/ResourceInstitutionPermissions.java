package kr.ac.pusan.pickle.security.institution;

import java.util.Arrays;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resource services keep their existing SYS subsets and their personal standing paths. */
@Component
public class ResourceInstitutionPermissions {
    private final InstitutionAuthorization authorization;
    private final OrgAdministrationLock barrier;
    private final LifecycleActors actors;

    public ResourceInstitutionPermissions(InstitutionAuthorization authorization, OrgAdministrationLock barrier,
            LifecycleActors actors) {
        this.authorization = authorization; this.barrier = barrier; this.actors = actors;
    }

    /** Called before domain locks on a protected administrator mutation. */
    public AuthenticatedUser currentForWrite(AuthenticatedUser actor) {
        barrier.acquire();
        return actors.currentAfterAdministrationLock(actor);
    }

    public AuthenticatedUser currentAfterLock(AuthenticatedUser actor) { return actors.currentAfterAdministrationLock(actor); }

    /** Explicit read admission is distinct from whether its scoped collection currently has rows. */
    public void requireReadSource(AuthenticatedUser actor, InstitutionWork... work) {
        if (actor.role().isSysTier() || Arrays.stream(work).anyMatch(item -> !authorization.sources(actor, item).isEmpty())) { return; }
        throw new kr.ac.pusan.pickle.common.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                kr.ac.pusan.pickle.common.error.ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", "이 조회 업무를 수행할 권한이 없습니다.");
    }

    public boolean resource(AuthenticatedUser actor, InstitutionWork work, @Nullable Long institutionId,
            @Nullable Long workspaceId, boolean historical, UserRole... sysRoles) {
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return (historical ? authorization.historicalResourceTarget(work.group(), institutionId, workspaceId)
                : authorization.resourceTarget(work.group(), institutionId, workspaceId))
                .map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
    }

    public boolean workspace(AuthenticatedUser actor, InstitutionWork work, long workspaceId, UserRole... sysRoles) {
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return authorization.workspaceTarget(workspaceId).map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
    }

    /** The caller already selected this stable public id from a live workspace row. */
    public boolean resolvedWorkspace(AuthenticatedUser actor, InstitutionWork work, java.util.UUID actualWorkspaceId, UserRole... sysRoles) {
        if (work.boundary() != InstitutionWork.Boundary.WORKSPACE_SUPPORT) { return false; }
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return authorization.decision(actor, work, InstitutionWorkTarget.workspace(actualWorkspaceId)).allowed();
    }

    public boolean workspacePair(AuthenticatedUser actor, InstitutionWork first, InstitutionWork second,
            long workspaceId, UserRole... sysRoles) {
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return authorization.workspaceTarget(workspaceId)
                .map(target -> authorization.sameSourceDecision(actor, first, second, target).allowed()).orElse(false);
    }

    public boolean institution(AuthenticatedUser actor, InstitutionWork work, long institutionId, UserRole... sysRoles) {
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return authorization.institutionTarget(institutionId).map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
    }

    public boolean global(AuthenticatedUser actor, InstitutionWork work, UserRole... sysRoles) {
        if (actor.role().isSysTier()) { return Arrays.asList(sysRoles).contains(actor.role()); }
        return authorization.decision(actor, work, InstitutionWorkTarget.global()).allowed();
    }
}
