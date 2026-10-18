package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import kr.ac.pusan.pickle.user.UserRole;

/** Only disposable local databases are upgraded from the previous schema. */
class InstitutionCutoverMigrationTest {
    private EmbeddedPostgres postgres;
    private JdbcTemplate jdbc;

    @BeforeEach
    void startPreviousSchema() throws Exception {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        migration("142").migrate();
    }

    @AfterEach
    void closeSyntheticDatabase() throws Exception {
        if (postgres != null) { postgres.close(); }
    }

    @Test
    void existingRowsConvertIndependentlyWithoutChangingIdentityOrSavedDeliveryRecords() {
        long a = org();
        long b = org();
        long fullAndViewer = user(UserRole.ORG_ADMIN, "ACTIVE");
        long inactiveManager = user(UserRole.ORG_MANAGER, "DISABLED");
        long secondInactiveManager = user(UserRole.ORG_MANAGER, "PENDING_VERIFICATION");
        legacy(fullAndViewer, a, UserRole.ORG_ADMIN);
        legacy(fullAndViewer, b, UserRole.ORG_VIEWER);
        legacy(inactiveManager, a, UserRole.ORG_MANAGER);
        legacy(secondInactiveManager, b, UserRole.ORG_MANAGER);
        jdbc.update("update user_org_roles set request_mail=true where user_id=?", inactiveManager);
        jdbc.update("update orgs set admin_revision=17 where id in (?,?)", a, b);
        String usersBefore = usersSnapshot();
        List<String> membershipsBefore = membershipFacts();
        long mailRows = count("notifications") + count("request_notification_selections");
        long auditRows = count("audit_logs");

        migration("143").migrate();

        assertThat(usersSnapshot()).isEqualTo(usersBefore);
        assertThat(membershipFacts()).isEqualTo(membershipsBefore);
        assertThat(count("user_org_roles")).isEqualTo(4);
        assertThat(count("notifications") + count("request_notification_selections")).isEqualTo(mailRows);
        assertThat(count("audit_logs")).isEqualTo(auditRows);
        assertThat(jdbc.queryForList("select admin_revision from orgs where id in (?,?) order by id", Long.class, a,b)).containsExactly(18L,18L);
        InstitutionAssignmentStore store = new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec());
        var first = store.find(pub("users",fullAndViewer),pub("orgs",a)).orElseThrow();
        var second = store.find(pub("users",fullAndViewer),pub("orgs",b)).orElseThrow();
        assertThat(first.mode()).isEqualTo(InstitutionAssignment.Mode.FULL);
        assertThat(second.mode()).isEqualTo(InstitutionAssignment.Mode.SELECTED);
        assertThat(second.displayRole()).isEqualTo(UserRole.ORG_VIEWER);
        assertThat(second.work()).hasSize(43).doesNotContain(InstitutionWork.INSTITUTION_AUDIT_READ, InstitutionWork.INSTITUTION_DELEGATE);
        var manager = store.find(pub("users",inactiveManager),pub("orgs",a)).orElseThrow();
        assertThat(manager.mode()).isEqualTo(InstitutionAssignment.Mode.SELECTED);
        assertThat(manager.work()).hasSize(77).contains(InstitutionWork.LLM_ROSTER_ISSUE,InstitutionWork.LLM_KEY_BUDGET,
                InstitutionWork.LLM_MODEL_CATALOGUE_READ,InstitutionWork.LLM_OBSERVE_SUPPORT_READ,InstitutionWork.GPU_OBSERVE_SUPPORT_READ)
                .doesNotContain(InstitutionWork.INSTITUTION_DELEGATE,InstitutionWork.NOTICE_MANAGE,InstitutionWork.GPU_RECALL);
        assertThat(manager.scopes()).hasSize(5);
        assertThat(manager.scopes().values()).allMatch(scope -> scope.mode()==InstitutionWorkspaceScope.Mode.ALL);
        assertThat(jdbc.queryForObject("select request_mail from user_org_roles where user_id=?",Boolean.class,inactiveManager)).isTrue();
        UUID futureWorkspace = jdbc.queryForObject("insert into workspaces(kind,name) values ('PROJECT',?) returning public_id",UUID.class,UUID.randomUUID().toString());
        var foreignKey = InstitutionWorkTarget.resource(pub("orgs",b),InstitutionWorkGroup.LLM,futureWorkspace);
        var ownKey = InstitutionWorkTarget.resource(pub("orgs",a),InstitutionWorkGroup.LLM,futureWorkspace);
        var managerAuthority = store.load(pub("users",inactiveManager));
        assertThat(managerAuthority.evaluate(InstitutionWork.LLM_KEY_COST_READ,foreignKey).allowed()).isFalse();
        assertThat(managerAuthority.evaluate(InstitutionWork.LLM_KEY_COST_READ,ownKey).allowed()).isTrue();
        assertThat(managerAuthority.evaluate(InstitutionWork.LLM_METADATA_SUPPORT_READ,foreignKey).allowed()).isTrue();
        assertThat(store.load(pub("users",fullAndViewer)).evaluate(InstitutionWork.LLM_KEY_COST_READ,foreignKey).allowed()).isTrue();
    }

    @Test
    void validExplicitPayloadsAndRevisionsSurviveAndFutureSequenceValuesRemainFresh() {
        long a=org(); long b=org();
        long explicit=user(UserRole.ORG_VIEWER,"ACTIVE");
        long old=user(UserRole.ORG_MANAGER,"ACTIVE");
        var codec=new InstitutionAssignmentCodec();
        var selected=InstitutionAssignment.selected(pub("users",explicit),pub("orgs",a),Set.of(InstitutionWork.NOTICE_READ),Map.of(),9001);
        var encoded=codec.encode(selected);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,'ORG_VIEWER',?,?::jsonb,9001)",explicit,a,encoded.mode(),encoded.payload());
        legacy(old,b,UserRole.ORG_MANAGER);
        String before=jdbc.queryForObject("select to_jsonb(r)::text from user_org_roles r where user_id=?",String.class,explicit);
        migration("143").migrate();
        assertThat(jdbc.queryForObject("select to_jsonb(r)::text from user_org_roles r where user_id=?",String.class,explicit)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select permission_revision from user_org_roles where user_id=?",Long.class,old)).isGreaterThan(9001);
        assertThat(jdbc.queryForObject("select admin_revision from orgs where id=?",Long.class,a)).isZero();
        assertThat(jdbc.queryForObject("select nextval('institution_permission_revision_seq')",Long.class)).isGreaterThan(9002);
    }

    @Test
    void emptyAndDeletedListedScopesRemainListedAndNeverBecomeAll() {
        long a=org(); long account=user(UserRole.ORG_VIEWER,"ACTIVE");
        UUID missingWorkspace=UUID.randomUUID();
        var codec=new InstitutionAssignmentCodec();
        var assignment=InstitutionAssignment.selected(pub("users",account),pub("orgs",a),
                Set.of(InstitutionWork.VM_METADATA_READ,InstitutionWork.LLM_METADATA_READ),
                Map.of(InstitutionWorkGroup.VM,InstitutionWorkspaceScope.listed(List.of(missingWorkspace)),
                        InstitutionWorkGroup.LLM,InstitutionWorkspaceScope.listed(List.of())),1);
        var encoded=codec.encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,'ORG_VIEWER',?,?::jsonb,nextval('institution_permission_revision_seq'))",account,a,encoded.mode(),encoded.payload());
        migration("143").migrate();
        var after=new InstitutionAssignmentStore(jdbc,codec).load(pub("users",account));
        assertThat(after.assignments().getFirst().scopes()).isEqualTo(assignment.scopes());
        UUID future=UUID.randomUUID();
        assertThat(after.evaluate(InstitutionWork.VM_METADATA_READ,InstitutionWorkTarget.resource(pub("orgs",a),InstitutionWorkGroup.VM,future)).allowed()).isFalse();
        assertThat(after.evaluate(InstitutionWork.LLM_METADATA_READ,InstitutionWorkTarget.resource(pub("orgs",a),InstitutionWorkGroup.LLM,future)).allowed()).isFalse();
    }

    @Test
    void malformedExplicitStateAbortsBeforeAnyLegacyRowOrRosterEpochChanges() {
        long a=org(); long valid=user(UserRole.ORG_MANAGER,"ACTIVE"); long malformed=user(UserRole.ORG_VIEWER,"ACTIVE");
        legacy(valid,a,UserRole.ORG_MANAGER);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,'ORG_VIEWER','SELECTED',?::jsonb,1)",
                malformed,a,"{\"work\":[\"unknown.work\"],\"scopes\":{}}");
        String before=rowsSnapshot();
        assertThatThrownBy(()->migration("143").migrate()).hasStackTraceContaining("invalid explicit membership state");
        assertThat(rowsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select admin_revision from orgs where id=?",Long.class,a)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='143' and success",Long.class)).isZero();
    }

    @Test
    void selectedDisplayContradictionIsAnExceptionInsteadOfAnAutomaticRoleRepair() {
        long a=org(); long account=user(UserRole.ORG_MANAGER,"ACTIVE");
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,'ORG_MANAGER','SELECTED',?::jsonb,1)",
                account,a,"{\"work\":[\"notice.read\"],\"scopes\":{}}");
        String before=usersSnapshot();
        assertThatThrownBy(()->migration("143").migrate()).hasStackTraceContaining("invalid explicit membership state");
        assertThat(usersSnapshot()).isEqualTo(before);
    }

    @Test
    void orphanOrgDisplayAndNonOrgMembershipContradictionsAreNeverPromoted() {
        long a=org(); long plain=user(UserRole.USER,"ACTIVE");
        legacy(plain,a,UserRole.ORG_ADMIN);
        user(UserRole.ORG_MANAGER,"ACTIVE");
        String before=usersSnapshot();
        assertThatThrownBy(()->migration("143").migrate()).hasStackTraceContaining("account role and membership sources disagree");
        assertThat(usersSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from user_org_roles where permission_mode is null",Long.class)).isEqualTo(1);
    }

    @Test
    void futureRoleOnlyInsertsAndContradictoryRoleOnlyUpdatesFailAtTheDatabaseBoundary() {
        long a=org(); long account=user(UserRole.ORG_ADMIN,"ACTIVE");legacy(account,a,UserRole.ORG_ADMIN);
        migration("143").migrate();
        long other=user(UserRole.ORG_VIEWER,"ACTIVE");
        assertThatThrownBy(()->legacy(other,a,UserRole.ORG_VIEWER)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update user_org_roles set role='ORG_VIEWER' where user_id=?",account)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select role::text from user_org_roles where user_id=?",String.class,account)).isEqualTo("ORG_ADMIN");
    }

    @Test
    void aFreshDatabaseGetsNoGrantAndKeepsTheRevisionSequenceUnallocated() {
        migration("143").migrate();
        assertThat(count("users")).isZero();assertThat(count("orgs")).isZero();assertThat(count("user_org_roles")).isZero();
        assertThat(jdbc.queryForObject("select is_called from institution_permission_revision_seq",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select nextval('institution_permission_revision_seq')",Long.class)).isEqualTo(1);
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(postgres.getPostgresDatabase()).locations("classpath:db/migration").target(target).load();
    }
    private long org() { return jdbc.queryForObject("insert into orgs(name) values (?) returning id",Long.class,UUID.randomUUID().toString()); }
    private long user(UserRole role,String status) {
        return jdbc.queryForObject("insert into users(email,password_hash,name,role,status) values(?,?,?,?::user_role,?::user_status) returning id",
                Long.class,UUID.randomUUID()+"@example.test","synthetic","migration fixture",role.name(),status);
    }
    private void legacy(long user,long org,UserRole role) { jdbc.update("insert into user_org_roles(user_id,org_id,role) values(?,?,?::user_role)",user,org,role.name()); }
    private UUID pub(String table,long id) { return jdbc.queryForObject("select public_id from "+table+" where id=?",UUID.class,id); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }
    private String usersSnapshot() { return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(u) order by id),'[]'::jsonb)::text from users u",String.class); }
    private String rowsSnapshot() { return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by user_id,org_id),'[]'::jsonb)::text from user_org_roles r",String.class); }
    private List<String> membershipFacts() { return jdbc.queryForList("select (to_jsonb(r)-'permission_mode'-'permission_payload'-'permission_revision')::text from user_org_roles r order by user_id,org_id",String.class); }
}
