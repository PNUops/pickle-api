package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InstitutionResourceQueryTest {
    private static final UUID ACCOUNT = new UUID(1, 1);
    private static final UUID ORG_A = new UUID(2, 1);
    private static final UUID ORG_B = new UUID(2, 2);
    private static final UUID WS_A = new UUID(3, 1);
    private static final UUID WS_B = new UUID(3, 2);
    private static EmbeddedPostgres database;
    private static JdbcTemplate jdbc;
    private static InstitutionResourceQueries queries;

    @BeforeAll
    static void syntheticDatabase() throws Exception {
        database = EmbeddedPostgres.start(); jdbc = new JdbcTemplate(database.getPostgresDatabase());
        jdbc.execute("create table orgs(id bigint primary key,public_id uuid not null)");
        jdbc.execute("create table workspaces(id bigint primary key,public_id uuid not null,deleted_at timestamptz)");
        jdbc.execute("create table vms(id bigint primary key,org_id bigint,workspace_id bigint,status text default 'STOPPED')");
        jdbc.execute("create table requests(id bigint primary key,org_id bigint,workspace_id bigint)");
        jdbc.execute("create table gpu_allocations(id bigint primary key,org_id bigint,workspace_id bigint,connection_status text default 'ATTACHED',attached_at timestamptz default now())");
        jdbc.execute("create table domains(id bigint primary key,org_id bigint,workspace_id bigint,vm_id bigint,kind text)");
        jdbc.execute("create table routes(id bigint primary key,domain_id bigint,status text)");
        jdbc.execute("create table certificates(id bigint primary key,domain_id bigint)");
        queries = new InstitutionResourceQueries(jdbc, new InstitutionAuthorization(jdbc));
    }

    @AfterAll
    static void closeDatabase() throws Exception { if (database != null) { database.close(); } }

    @BeforeEach
    void relations() {
        jdbc.execute("truncate orgs,workspaces,vms,requests,gpu_allocations,domains,routes,certificates");
        jdbc.update("insert into orgs values(1,?),(2,?)", ORG_A, ORG_B);
        jdbc.update("insert into workspaces(id,public_id) values(1,?),(2,?)", WS_A, WS_B);
        jdbc.update("insert into vms(id,org_id,workspace_id) values(1,1,1),(2,2,1),(3,2,2)");
        jdbc.update("insert into gpu_allocations(id,org_id,workspace_id) values(1,1,1),(2,2,1),(3,2,2)");
        jdbc.update("insert into domains values(1,1,1,null,'EXTERNAL'),(2,2,2,null,'EXTERNAL'),"
                + "(3,2,1,2,'CUSTOM'),(4,2,2,3,'PLATFORM')");
        jdbc.update("insert into routes values(1,3,'APPLIED'),(2,4,'REMOVED'),(3,1,'APPLIED')");
        jdbc.update("insert into certificates values(1,null),(2,3),(3,4)");
    }

    @Test
    void directVmMetadataKeepsActualInstitutionAndWorkspaceTogether() {
        assertThat(queries.vmMetadata(actor(selected(ORG_A, InstitutionWork.VM_METADATA_READ, WS_A))))
                .containsExactly(1L);
    }

    @Test
    void supportVmMetadataKeepsWorkspaceButDoesNotInventInstitutionOwnership() {
        assertThat(queries.vmMetadata(actor(selected(ORG_A, InstitutionWork.VM_METADATA_SUPPORT_READ, WS_A))))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void completeSourcesAreOrBranchesWithoutDuplicatingRowsOrBorrowingScopes() {
        var a = selected(ORG_A, InstitutionWork.VM_METADATA_READ, WS_A);
        var b = selected(ORG_B, InstitutionWork.VM_METADATA_SUPPORT_READ, WS_B);
        assertThat(queries.vmMetadata(actor(a, b))).containsExactlyInAnyOrder(1L, 3L);
        var overlap = selected(ORG_B, InstitutionWork.VM_METADATA_SUPPORT_READ, WS_A);
        assertThat(queries.vmMetadata(actor(a, overlap))).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void emptyListedAndObservationOnlyNeverBecomeMetadataAll() {
        var empty = selected(ORG_A, InstitutionWork.VM_METADATA_SUPPORT_READ);
        var observe = selected(ORG_B, InstitutionWork.VM_OBSERVE, WS_A);
        assertThat(queries.vmMetadata(actor(empty, observe))).isEmpty();
    }

    @Test
    void allIncludesFutureKnownWorkspacesButNotUnresolvedWorkspaceRelations() {
        var all = InstitutionAssignment.selected(ACCOUNT, ORG_A, Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all()), 1);
        jdbc.update("insert into workspaces(id,public_id) values(3,?)", UUID.randomUUID());
        jdbc.update("insert into vms(id,org_id,workspace_id) values(4,1,3),(5,1,null),(6,1,999)");
        assertThat(queries.vmMetadata(actor(all))).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    void historicalMetadataRetainsClosedWorkspaceRows() {
        jdbc.update("update workspaces set deleted_at=now() where id=1");
        assertThat(queries.vmMetadata(actor(selected(ORG_A, InstitutionWork.VM_METADATA_SUPPORT_READ, WS_A))))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void historicalAclManagementReferencesRemainSeparateFromLiveResourceCommands() {
        jdbc.update("update workspaces set deleted_at=now() where id=1");
        assertThat(queries.vmReferences(actor(selected(ORG_A, InstitutionWork.VM_ACL_SUPPORT_MANAGE, WS_A))))
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(queries.gpuAllocations(actor(selected(ORG_A, InstitutionWork.GPU_ACL_SUPPORT_MANAGE, WS_A))))
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(queries.domainReferences(actor(selected(ORG_A, InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE, WS_A))))
                .containsExactly(1L);
        assertThat(queries.vmReferences(actor(selected(ORG_A, InstitutionWork.VM_PERIOD, WS_A)))).isEmpty();
        assertThat(queries.vmReferences(actor(selected(ORG_A, InstitutionWork.VM_ACL_SUPPORT_MANAGE, WS_B))))
                .containsExactly(3L);
    }

    @Test
    void externalDomainWorkDoesNotOpenVmLinkedPublicationMetadata() {
        assertThat(queries.domains(actor(selected(ORG_A, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ, WS_A))))
                .containsExactly(1L);
        assertThat(queries.domains(actor(selected(ORG_A, InstitutionWork.VM_METADATA_SUPPORT_READ, WS_A))))
                .containsExactly(3L);
    }

    @Test
    void routeMetadataDoesNotBorrowExternalDomainReadFromAnotherSource() {
        var vm = selected(ORG_A, InstitutionWork.VM_PUBLICATION_READ, WS_B);
        var external = selected(ORG_B, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ, WS_A);
        assertThat(queries.routes(actor(vm, external))).isEmpty();
        assertThat(queries.domains(actor(vm, external))).containsExactly(1L);
    }

    @Test
    void publicationDirectReadUsesDomainInstitutionRatherThanTheLinkedVmInstitution() {
        jdbc.update("update domains set org_id=1 where id=3");
        assertThat(queries.domains(actor(selected(ORG_A, InstitutionWork.VM_PUBLICATION_READ, WS_A))))
                .containsExactly(3L);
        assertThat(queries.domains(actor(selected(ORG_B, InstitutionWork.VM_PUBLICATION_READ, WS_A))))
                .isEmpty();
    }

    @Test
    void certificateProjectionUsesVmOwnershipAndKeepsSharedCertificateGlobalSeparate() {
        jdbc.update("update domains set org_id=1,workspace_id=2 where id=3");
        assertThat(queries.certificates(actor(selected(ORG_B, InstitutionWork.VM_PUBLICATION_READ, WS_A))))
                .containsExactly(2L);
        assertThat(queries.certificates(actor(selected(ORG_A, InstitutionWork.VM_PUBLICATION_READ, WS_B))))
                .isEmpty();
        var shared = InstitutionAssignment.selected(ACCOUNT, ORG_A,
                Set.of(InstitutionWork.SHARED_CERTIFICATE_READ), Map.of(), 1);
        assertThat(queries.certificates(actor(shared))).containsExactly(1L);
    }

    @Test
    void gpuObservationSupportDoesNotBorrowMetadataScopeOrGrantOtherWorkspaces() {
        var observe = selected(ORG_A, InstitutionWork.GPU_OBSERVE_SUPPORT_READ, WS_A);
        var metadata = selected(ORG_B, InstitutionWork.GPU_METADATA_READ, WS_B);
        assertThat(queries.gpuAllocations(actor(observe, metadata))).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(queries.gpuAllocations(actor(observe))).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void workspaceCommandsOnlySelectTheirOwnWorkspaceReferences() {
        var command = selected(ORG_A, InstitutionWork.WORKSPACE_INFO_UPDATE, WS_A);
        assertThat(queries.workspaces(actor(command))).containsExactly(1L);
        assertThat(queries.workspaces(actor(selected(ORG_A, InstitutionWork.VM_POWER, WS_A)))).isEmpty();
    }

    @Test
    void commandSelectorsKeepWorkInstitutionAndWorkspaceInTheSameSqlBranch() {
        var first = selected(ORG_A, InstitutionWork.VM_PERIOD, WS_A);
        var other = selected(ORG_B, InstitutionWork.VM_POWER, WS_B);
        assertThat(queries.vmReferences(actor(first, other))).containsExactlyInAnyOrder(1L, 3L);
        assertThat(queries.vmMetadata(actor(first, other))).isEmpty();
        assertThat(queries.domainReferences(actor(selected(ORG_A, InstitutionWork.DOMAIN_RENEWAL, WS_A)))).containsExactly(1L);
        assertThat(queries.domainReferences(actor(selected(ORG_B, InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY, WS_B)))).isEmpty();
    }

    @Test
    void requestWorkspaceReferencesDoNotRequireAnExistingResourceInstitution() {
        var roster = selected(ORG_A, InstitutionWork.VM_ROSTER_ISSUE, WS_B);
        assertThat(queries.requestWorkspaces(actor(roster), ORG_A)).containsExactly(2L);
        assertThat(queries.requestWorkspaces(actor(roster), ORG_B)).isEmpty();
        jdbc.update("update workspaces set deleted_at=now() where id=2");
        assertThat(queries.requestWorkspaces(actor(roster), ORG_A)).isEmpty();
    }

    @Test
    void announcementQualificationAndWorkspaceScopeCannotComeFromDifferentSources() {
        jdbc.update("insert into requests values(1,1,1),(2,2,2)");
        jdbc.update("delete from vms");
        var a = selected(ORG_A, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND, WS_B);
        var b = selected(ORG_B, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND, WS_A);
        assertThat(queries.announcementWorkspaces(actor(a, b), null)).isEmpty();
        assertThat(queries.workspaceCommandWorkIds(actor(a, b), 1, null)).isEmpty();
        var complete = selected(ORG_A, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND, WS_A);
        assertThat(queries.announcementWorkspaces(actor(complete), null)).containsExactly(1L);
        assertThat(queries.workspaceCommandWorkIds(actor(complete), 1, null)).containsExactly("workspace.announcement.send");
    }

    @Test
    void removalReferencesUseHistoricalWorkspaceWithoutOpeningActiveRoutesInThatWorkspace() {
        jdbc.update("update workspaces set deleted_at=now() where id=2");
        jdbc.update("insert into routes values(4,4,'APPLIED')");
        var operate = selected(ORG_B, InstitutionWork.VM_PUBLICATION_OPERATE, WS_B);
        assertThat(queries.routes(actor(operate))).containsExactly(2L);
        var metadata = selected(ORG_B, InstitutionWork.VM_PUBLICATION_READ, WS_B);
        assertThat(queries.routes(actor(metadata))).containsExactlyInAnyOrder(2L, 4L);
    }

    @Test
    void invalidInstitutionContextCannotUseItsDisplayRoleAsAQueryGrant() {
        var invalid = new AuthenticatedUser(1L, ACCOUNT, "synthetic@example.invalid", UserRole.ORG_ADMIN,
                Map.of(1L, UserRole.ORG_ADMIN), 0, InstitutionActorContext.invalid(InstitutionActorContext.Failure.UNCONVERTED));
        assertThat(queries.vmMetadata(invalid)).isEmpty();
        var sys = new AuthenticatedUser(1L, ACCOUNT, "synthetic@example.invalid", UserRole.SYS_VIEWER,
                Map.of(), 0, InstitutionActorContext.valid(new InstitutionAuthority(ACCOUNT, List.of())));
        assertThat(queries.vmMetadata(sys)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    private static InstitutionAssignment selected(UUID org, InstitutionWork work, UUID... workspaces) {
        return InstitutionAssignment.selected(ACCOUNT, org, Set.of(work),
                Map.of(work.group(), InstitutionWorkspaceScope.listed(List.of(workspaces))), 1);
    }

    private static AuthenticatedUser actor(InstitutionAssignment... sources) {
        InstitutionAuthority authority = new InstitutionAuthority(ACCOUNT, List.of(sources));
        return new AuthenticatedUser(1L, ACCOUNT, "synthetic@example.invalid", authority.displayRole().orElse(UserRole.USER),
                Map.of(), 0, InstitutionActorContext.valid(authority));
    }
}
