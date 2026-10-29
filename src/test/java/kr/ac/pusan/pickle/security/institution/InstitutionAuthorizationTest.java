package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InstitutionAuthorizationTest {
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static InstitutionAuthorization authorization;
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID FIRST_ORG = UUID.randomUUID();
    private static final UUID SECOND_ORG = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @BeforeAll
    static void syntheticRelations() throws Exception {
        postgres = EmbeddedPostgres.start(); jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        jdbc.execute("create table orgs(id bigint primary key,public_id uuid not null)");
        jdbc.execute("create table workspaces(id bigint primary key,public_id uuid not null,deleted_at timestamptz)");
        jdbc.update("insert into orgs values(1,?),(2,?)", FIRST_ORG, SECOND_ORG);
        jdbc.update("insert into workspaces values(1,?,null),(2,?,now())", WORKSPACE, UUID.randomUUID());
        authorization = new InstitutionAuthorization(jdbc);
    }
    @AfterAll
    static void closeSyntheticDatabase() throws Exception { if (postgres != null) { postgres.close(); } }

    @Test
    void nonnullMissingInstitutionMustNotBecomeAGlobalSupportTarget() {
        assertThat(authorization.resourceTarget(InstitutionWorkGroup.LLM, 999L, 1L)).isEmpty();
        assertThat(authorization.historicalResourceTarget(InstitutionWorkGroup.LLM, 999L, 1L)).isEmpty();
    }

    @Test
    void actualNullInstitutionSupportKeepsItsKnownWorkspaceWithoutInventedOwnership() {
        InstitutionWorkTarget target = authorization.resourceTarget(InstitutionWorkGroup.LLM, null, 1L).orElseThrow();
        AuthenticatedUser full = actor(UserRole.ORG_ADMIN, InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11));
        assertThat(authorization.decision(full, InstitutionWork.LLM_KEY_COST_READ, target).allowed()).isTrue();
        assertThat(authorization.decision(full, InstitutionWork.LLM_OBSERVE_SUPPORT_READ, target).allowed()).isTrue();
        assertThat(authorization.decision(full, InstitutionWork.LLM_KEY_BUDGET, target).allowed()).isFalse();
        assertThat(authorization.resourceTarget(InstitutionWorkGroup.LLM, null, 999L)).isEmpty();
    }

    @Test
    void historicalRelationFactoryPreservesClosedWorkspaceWithoutOpeningCurrentCommands() {
        assertThat(authorization.resourceTarget(InstitutionWorkGroup.VM, 1L, 2L)).isEmpty();
        assertThat(authorization.historicalResourceTarget(InstitutionWorkGroup.VM, 1L, 2L)).isPresent();
        assertThat(authorization.workspaceTarget(2)).isEmpty();
        assertThat(authorization.historicalWorkspaceTarget(2)).isPresent();
    }

    @Test
    void invalidInstitutionContextsAndSystemRolesReceiveNoInstitutionFallback() {
        AuthenticatedUser invalid = new AuthenticatedUser(1L, ACCOUNT, "synthetic@example.invalid", UserRole.ORG_ADMIN, Map.of());
        assertThat(authorization.authority(invalid)).isEmpty();
        assertThat(authorization.sources(invalid, InstitutionWork.NOTICE_MANAGE)).isEmpty();
        AuthenticatedUser sys = actor(UserRole.SYS_ADMIN, InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11));
        assertThat(authorization.decision(sys, InstitutionWork.NOTICE_MANAGE, InstitutionWorkTarget.global()).allowed()).isFalse();
    }

    @Test
    void compositePaidWorkRequiresOneCompleteSource() {
        InstitutionAssignment decide = selected(FIRST_ORG, InstitutionWork.LLM_REQUEST_DECIDE);
        InstitutionAssignment budget = selected(SECOND_ORG, InstitutionWork.LLM_KEY_BUDGET);
        AuthenticatedUser actor = actor(UserRole.ORG_MANAGER, decide, budget);
        InstitutionWorkTarget target = authorization.resourceTarget(InstitutionWorkGroup.LLM, 1L, 1L).orElseThrow();
        assertThat(authorization.sameSourceDecision(actor, InstitutionWork.LLM_REQUEST_DECIDE, InstitutionWork.LLM_KEY_BUDGET, target).allowed()).isFalse();
        InstitutionAssignment both = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.LLM_REQUEST_DECIDE, InstitutionWork.LLM_KEY_BUDGET),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(WORKSPACE))), 13);
        assertThat(authorization.sameSourceDecision(actor(UserRole.ORG_MANAGER, both), InstitutionWork.LLM_REQUEST_DECIDE,
                InstitutionWork.LLM_KEY_BUDGET, target).sources()).extracting(InstitutionWorkDecision.GrantSource::revision).containsExactly(13L);
    }

    @Test
    void sourceSlicesKeepDirectSupportAndOnlyFullKeyFinanceBoundariesDistinct() {
        AuthenticatedUser full = actor(UserRole.ORG_ADMIN, InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11));
        assertThat(authorization.sources(full, InstitutionWork.LLM_KEY_COST_READ).getFirst().fullGlobalResourceRead()).isTrue();
        assertThat(authorization.sources(full, InstitutionWork.LLM_KEY_COST_READ).getFirst().requiredInstitutionId()).isNull();
        assertThat(authorization.sources(full, InstitutionWork.LLM_KEY_BUDGET).getFirst().requiredInstitutionId()).isEqualTo(FIRST_ORG);
        InstitutionAuthorizationSource support = authorization.sources(full, InstitutionWork.LLM_OBSERVE_SUPPORT_READ).getFirst();
        assertThat(support.boundary()).isEqualTo(InstitutionWork.Boundary.RESOURCE_SUPPORT);
        assertThat(support.requiredInstitutionId()).isNull();
        assertThat(support.fullGlobalResourceRead()).isFalse();
        assertThatThrownBy(() -> authorization.sources(full, InstitutionWork.LLM_KEY_COST_READ).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listedEmptyDoesNotWidenAndDecisionKindFactoriesAreExactlyFourResourceKinds() {
        InstitutionAssignment empty = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of())), 11);
        assertThat(authorization.sources(actor(UserRole.ORG_VIEWER, empty), InstitutionWork.LLM_OBSERVE)).isEmpty();
        assertThat(InstitutionAuthorization.requestDecisionWork(null)).isEmpty();
        assertThat(InstitutionAuthorization.group(null)).isEmpty();
        assertThat(List.of(ResourceType.values())).hasSize(4);
        for (ResourceType type : ResourceType.values()) {
            assertThat(InstitutionAuthorization.requestDecisionWork(type)).isPresent();
            assertThat(authorization.requestDecisionTarget(type, 1, 1)).isPresent();
        }
    }

    private static InstitutionAssignment selected(UUID org, InstitutionWork work) {
        return InstitutionAssignment.selected(ACCOUNT, org, Set.of(work),
                Map.of(work.group(), InstitutionWorkspaceScope.all()), 11);
    }
    private static AuthenticatedUser actor(UserRole role, InstitutionAssignment... assignments) {
        return new AuthenticatedUser(1L, ACCOUNT, "synthetic@example.invalid", role, Map.of(), 0,
                InstitutionActorContext.valid(new InstitutionAuthority(ACCOUNT, List.of(assignments))));
    }
}
