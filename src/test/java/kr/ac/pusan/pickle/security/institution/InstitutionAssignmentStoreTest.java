package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.user.UserRole;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/** Only an embedded synthetic database is used; no application context or runtime adapter is registered. */
class InstitutionAssignmentStoreTest {
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static InstitutionAssignmentStore store;
    private static AssignmentStorageMembershipFixture legacy;
    private static String legacyBeforeMigration;
    private static boolean sequenceInitiallyUnallocated;

    @BeforeAll
    static void startSyntheticDatabaseAndUpgradeLegacyRows() throws Exception {
        postgres = EmbeddedPostgres.start();
        var source = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("141").load().migrate();
        legacy = membership(UserRole.ORG_MANAGER);
        jdbc.update("update user_org_roles set request_mail=true where user_id=? and org_id=?", legacy.userId(), legacy.orgId());
        legacyBeforeMigration = jdbc.queryForObject("select to_jsonb(r)::text from user_org_roles r where user_id=? and org_id=?",
                String.class, legacy.userId(), legacy.orgId());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        sequenceInitiallyUnallocated = !Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_called from institution_permission_revision_seq", Boolean.class));
        store = new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec());
    }

    @AfterAll
    static void closeSyntheticDatabase() throws Exception {
        if (postgres != null) { postgres.close(); }
    }

    @Test
    void nullableExpansionPreservesLegacyRowsAndThePreviousInsertShapeWithoutBackfill() {
        assertThat(jdbc.queryForObject("""
                select (to_jsonb(r)-'permission_mode'-'permission_payload'-'permission_revision')::text
                  from user_org_roles r where user_id=? and org_id=?
                """, String.class, legacy.userId(), legacy.orgId())).isEqualTo(legacyBeforeMigration);
        assertThat(jdbc.queryForObject("select num_nonnulls(permission_mode,permission_payload,permission_revision) from user_org_roles where user_id=? and org_id=?",
                Integer.class, legacy.userId(), legacy.orgId())).isZero();
        assertThat(sequenceInitiallyUnallocated).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='142' and success", Integer.class)).isEqualTo(1);
        assertReason(() -> store.find(legacy.account(), legacy.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
        AssignmentStorageMembershipFixture previousShape = membership(UserRole.ORG_ADMIN);
        assertReason(() -> store.find(previousShape.account(), previousShape.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
    }

    @Test
    void publicUuidMappingAndFullOrSelectedStorageRoundTripExactly() {
        AssignmentStorageMembershipFixture fullMember = membership(UserRole.ORG_ADMIN);
        InstitutionAssignment full = save(InstitutionAssignment.full(fullMember.account(), fullMember.institution(), Long.MAX_VALUE), 0);
        assertThat(full.revision()).isPositive().isNotEqualTo(Long.MAX_VALUE);
        assertThat(store.find(fullMember.account(), fullMember.institution())).contains(full);
        assertThat(full.accountId()).isEqualTo(fullMember.account());
        assertThat(full.institutionId()).isEqualTo(fullMember.institution());

        AssignmentStorageMembershipFixture selectedMember = membership(UserRole.ORG_MANAGER);
        UUID workspace = workspace();
        InstitutionAssignment selected = save(InstitutionAssignment.selected(selectedMember.account(), selectedMember.institution(),
                Set.of(InstitutionWork.VM_POWER, InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(workspace)),
                        InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of())), 0), 0);
        assertThat(store.find(selectedMember.account(), selectedMember.institution())).contains(selected);
        assertThat(selected.scopes().get(InstitutionWorkGroup.LLM).mode()).isEqualTo(InstitutionWorkspaceScope.Mode.LISTED);
        assertThat(selected.scopes().get(InstitutionWorkGroup.LLM).workspaceIds()).isEmpty();
        assertThat(store.load(selectedMember.account()).evaluate(InstitutionWork.LLM_OBSERVE,
                InstitutionWorkTarget.resource(selectedMember.institution(), InstitutionWorkGroup.LLM, workspace)).allowed()).isFalse();
    }

    @Test
    void databaseRejectsPartialModePayloadTypeAndInvalidRevisionWithoutChangingLegacyState() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_ADMIN);
        assertThatThrownBy(() -> jdbc.update("update user_org_roles set permission_mode='FULL' where user_id=? and org_id=?",
                member.userId(), member.orgId())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_org_roles_permission_state");
        assertThatThrownBy(() -> raw(member, "FUTURE", "{}", 1)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_org_roles_permission_mode");
        assertThatThrownBy(() -> raw(member, "FULL", "[]", 1)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_org_roles_permission_payload");
        assertThatThrownBy(() -> raw(member, "FULL", "{}", 0)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_org_roles_permission_revision");
        assertReason(() -> store.find(member.account(), member.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
    }

    @Test
    void oneUnknownStoredSourceFailsTheWholeAuthorityReadInsteadOfBeingOmitted() {
        AssignmentStorageMembershipFixture first = membership(UserRole.ORG_MANAGER);
        AssignmentStorageMembershipFixture second = anotherInstitution(first, UserRole.ORG_MANAGER);
        save(selected(first, InstitutionWork.NOTICE_MANAGE), 0);
        raw(second, "SELECTED", "{\"work\":[\"unknown.work\"],\"scopes\":{}}", 1);

        assertReason(() -> store.find(second.account(), second.institution()), InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        assertReason(() -> store.load(first.account()), InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        assertReason(() -> save(selected(second, InstitutionWork.NOTICE_MANAGE), 1), InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
    }

    @Test
    void roleContradictionsFailOnReadAndSaveRatherThanReinterpretingSelectedWork() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        InstitutionAssignment assignment = save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
        jdbc.update("update user_org_roles set role='ORG_VIEWER' where user_id=? and org_id=?", member.userId(), member.orgId());
        assertReason(() -> store.load(member.account()), InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH);
        assertReason(() -> save(selected(member, InstitutionWork.NOTICE_MANAGE), assignment.revision()),
                InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH);

        AssignmentStorageMembershipFixture fullRole = membership(UserRole.ORG_ADMIN);
        assertReason(() -> save(selected(fullRole, InstitutionWork.NOTICE_MANAGE), 0), InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH);
        assertThat(jdbc.queryForObject("select permission_mode from user_org_roles where user_id=? and org_id=?",
                String.class, fullRole.userId(), fullRole.orgId())).isNull();
    }

    @Test
    void writesRequireATransactionAndAnExistingMembership() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        assertReason(() -> store.save(selected(member, InstitutionWork.NOTICE_MANAGE), 0),
                InstitutionAssignmentStorageException.Reason.TRANSACTION_REQUIRED);
        InstitutionAssignment missing = InstitutionAssignment.selected(member.account(), UUID.randomUUID(),
                Set.of(InstitutionWork.NOTICE_MANAGE), Map.of(), 0);
        assertReason(() -> save(missing, 0), InstitutionAssignmentStorageException.Reason.MISSING_MEMBERSHIP);
        assertThat(store.find(member.account(), missing.institutionId())).isEmpty();
    }

    @Test
    void anUnrelatedDatasourceTransactionCannotLeaveAnAutocommittedAssignmentAfterRollback() {
        var unrelatedSource = new DelegatingDataSource(jdbc.getDataSource());
        var unrelatedTransactions = new TransactionTemplate(new DataSourceTransactionManager(unrelatedSource));
        List<Integer> persistedFields = new ArrayList<>();
        List<InstitutionAssignmentStorageException> rejections = new ArrayList<>();
        for (boolean bindConnectionFirst : List.of(false, true)) {
            AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
            AtomicReference<InstitutionAssignmentStorageException> rejected = new AtomicReference<>();
            unrelatedTransactions.executeWithoutResult(status -> {
                if (bindConnectionFirst) { assertThat(store.find(member.account(), UUID.randomUUID())).isEmpty(); }
                try {
                    store.save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
                } catch (InstitutionAssignmentStorageException exception) {
                    rejected.set(exception);
                }
                status.setRollbackOnly();
            });
            persistedFields.add(jdbc.queryForObject("select num_nonnulls(permission_mode,permission_payload,permission_revision) from user_org_roles where user_id=? and org_id=?",
                    Integer.class, member.userId(), member.orgId()));
            rejections.add(rejected.get());
        }
        assertThat(persistedFields).containsExactly(0, 0);
        assertThat(rejections).allSatisfy(rejection -> assertThat(rejection).isNotNull()
                .extracting(InstitutionAssignmentStorageException::reason)
                .isEqualTo(InstitutionAssignmentStorageException.Reason.TRANSACTION_REQUIRED));
    }

    @Test
    void deletionAndIdenticalRegrantUseANewRevisionAndCannotReplayTheOldContext() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        InstitutionAssignment first = save(selected(member, InstitutionWork.VM_POWER), 0);
        String fingerprint = store.load(member.account()).fingerprint();
        jdbc.update("delete from user_org_roles where user_id=? and org_id=?", member.userId(), member.orgId());
        assertThat(store.find(member.account(), member.institution())).isEmpty();
        assertThat(store.load(member.account()).displayRole()).isEmpty();
        jdbc.update("insert into user_org_roles(user_id,org_id,role) values(?,?,'ORG_MANAGER')", member.userId(), member.orgId());
        assertReason(() -> store.find(member.account(), member.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
        InstitutionAssignment regrant = save(selected(member, InstitutionWork.VM_POWER), 0);

        assertThat(regrant.revision()).isGreaterThan(first.revision());
        assertThat(store.load(member.account()).fingerprint()).isNotEqualTo(fingerprint);
        assertReason(() -> save(selected(member, InstitutionWork.VM_POWER), first.revision()),
                InstitutionAssignmentStorageException.Reason.REVISION_CONFLICT);
        assertThat(store.find(member.account(), member.institution())).contains(regrant);
    }

    @Test
    void rolledBackRevisionNumbersAreConsumedAndNeverReused() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        InstitutionAssignment aborted = transactions.execute(status -> {
            InstitutionAssignment saved = store.save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
            status.setRollbackOnly();
            return saved;
        });
        assertThat(aborted).isNotNull();
        assertReason(() -> store.find(member.account(), member.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
        InstitutionAssignment committed = save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
        assertThat(committed.revision()).isGreaterThan(aborted.revision());
        InstitutionAssignment abortedUpdate = transactions.execute(status -> {
            InstitutionAssignment saved = store.save(selected(member, InstitutionWork.VM_POWER), committed.revision());
            status.setRollbackOnly();
            return saved;
        });
        assertThat(abortedUpdate).isNotNull();
        assertThat(store.find(member.account(), member.institution())).contains(committed);
        assertThat(save(selected(member, InstitutionWork.VM_POWER), committed.revision()).revision()).isGreaterThan(abortedUpdate.revision());
    }

    @Test
    void theSameDatasourceJpaCallerOwnsCommitAndRollbackWithoutAnIndependentStoreTransaction() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setPersistenceUnitName("synthetic-institution-storage");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject());
            manager.setDataSource(jdbc.getDataSource());
            var jpaTransactions = new TransactionTemplate(manager);
            AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
            InstitutionAssignment aborted = jpaTransactions.execute(status -> {
                InstitutionAssignment saved = store.save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
                status.setRollbackOnly();
                return saved;
            });
            assertThat(aborted).isNotNull();
            assertReason(() -> store.find(member.account(), member.institution()), InstitutionAssignmentStorageException.Reason.UNCONVERTED);
            InstitutionAssignment committed = jpaTransactions.execute(status -> store.save(selected(member, InstitutionWork.NOTICE_MANAGE), 0));
            assertThat(committed).isNotNull();
            assertThat(committed.revision()).isGreaterThan(aborted.revision());
            assertThat(store.find(member.account(), member.institution())).contains(committed);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void workChangesWithinManagerChangeAuthorityWithoutChangingTheAccountRoleOrTokens() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        InstitutionAssignment original = save(selected(member, InstitutionWork.NOTICE_MANAGE), 0);
        String fingerprint = store.load(member.account()).fingerprint();
        InstitutionAssignment changed = save(selected(member, InstitutionWork.VM_POWER), original.revision());

        assertThat(changed.displayRole()).isEqualTo(original.displayRole()).isEqualTo(UserRole.ORG_MANAGER);
        assertThat(changed.revision()).isGreaterThan(original.revision());
        assertThat(store.load(member.account()).fingerprint()).isNotEqualTo(fingerprint);
        assertThat(store.load(member.account()).evaluate(InstitutionWork.NOTICE_MANAGE, InstitutionWorkTarget.global()).allowed()).isFalse();
        assertThat(jdbc.queryForMap("select role::text,token_version,row_revision from users where id=?", member.userId()))
                .containsEntry("role", "ORG_MANAGER").containsEntry("token_version", 0).containsEntry("row_revision", 0L);
    }

    @Test
    void aMailOnlyLegacyColumnUpdatePreservesExactPayloadRevisionAndAuthority() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        InstitutionAssignment stored = save(selected(member, InstitutionWork.VM_REQUEST_DECIDE), 0);
        String payload = jdbc.queryForObject("select permission_payload::text from user_org_roles where user_id=? and org_id=?",
                String.class, member.userId(), member.orgId());
        String fingerprint = store.load(member.account()).fingerprint();
        jdbc.update("update user_org_roles set request_mail=true where user_id=? and org_id=?", member.userId(), member.orgId());

        assertThat(jdbc.queryForObject("select permission_payload::text from user_org_roles where user_id=? and org_id=?",
                String.class, member.userId(), member.orgId())).isEqualTo(payload);
        assertThat(store.find(member.account(), member.institution())).contains(stored);
        assertThat(store.load(member.account()).fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void concurrentDifferentInstitutionsPreserveBothCompleteSources() throws Exception {
        AssignmentStorageMembershipFixture first = membership(UserRole.ORG_MANAGER);
        AssignmentStorageMembershipFixture second = anotherInstitution(first, UserRole.ORG_MANAGER);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> save(selected(first, InstitutionWork.VM_POWER), 0));
            var b = threads.submit(() -> save(selected(second, InstitutionWork.NOTICE_MANAGE), 0));
            assertThat(a.get(10, TimeUnit.SECONDS).institutionId()).isEqualTo(first.institution());
            assertThat(b.get(10, TimeUnit.SECONDS).institutionId()).isEqualTo(second.institution());
        }
        InstitutionAuthority authority = store.load(first.account());
        assertThat(authority.assignments()).hasSize(2);
        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(second.institution(), InstitutionWorkGroup.VM, UUID.randomUUID())).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.NOTICE_MANAGE, InstitutionWorkTarget.global()).sources())
                .extracting(InstitutionWorkDecision.GrantSource::institutionId).containsExactly(second.institution());
    }

    @Test
    void concurrentSameMembershipUsesTheLockedCurrentRevision() throws Exception {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        int succeeded = 0;
        int conflicts = 0;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var futures = threads.invokeAll(List.of(
                    () -> save(selected(member, InstitutionWork.NOTICE_MANAGE), 0),
                    () -> save(selected(member, InstitutionWork.VM_POWER), 0)));
            for (var future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOfSatisfying(InstitutionAssignmentStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(InstitutionAssignmentStorageException.Reason.REVISION_CONFLICT));
                    conflicts++;
                }
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(store.find(member.account(), member.institution())).isPresent();
    }

    @Test
    void deletedWorkspaceIdsRemainListedAndDoNotBecomeAllOrIncludeNewWorkspaces() {
        AssignmentStorageMembershipFixture member = membership(UserRole.ORG_MANAGER);
        UUID previous = workspace();
        InstitutionAssignment stored = save(InstitutionAssignment.selected(member.account(), member.institution(),
                Set.of(InstitutionWork.VM_POWER), Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(previous))), 0), 0);
        jdbc.update("update workspaces set deleted_at=now() where public_id=?", previous);
        UUID replacement = workspace();

        assertThat(store.find(member.account(), member.institution())).contains(stored);
        assertThat(stored.scopes().get(InstitutionWorkGroup.VM).mode()).isEqualTo(InstitutionWorkspaceScope.Mode.LISTED);
        assertThat(stored.scopes().get(InstitutionWorkGroup.VM).workspaceIds()).containsExactly(previous);
        assertThat(store.load(member.account()).evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(member.institution(), InstitutionWorkGroup.VM, replacement)).allowed()).isFalse();
        // The future target resolver rejects the deleted target; storage never fabricates liveness.
    }

    @Test
    void emptyUserAndSystemContextsDoNotRewriteServerRoles() {
        for (UserRole role : List.of(UserRole.USER, UserRole.SYS_ADMIN)) {
            UUID account = UUID.randomUUID();
            long user = user(account, role);
            assertThat(store.load(account).displayRole()).isEmpty();
            assertThat(jdbc.queryForObject("select role::text from users where id=?", String.class, user)).isEqualTo(role.name());
        }
    }

    private static InstitutionAssignment save(InstitutionAssignment assignment, long expectedRevision) {
        return transactions.execute(status -> store.save(assignment, expectedRevision));
    }

    private static InstitutionAssignment selected(AssignmentStorageMembershipFixture membership, InstitutionWork work) {
        return InstitutionAssignment.selected(membership.account(), membership.institution(), Set.of(work),
                work.group() == null ? Map.of() : Map.of(work.group(), InstitutionWorkspaceScope.all()), 0);
    }

    private static AssignmentStorageMembershipFixture membership(UserRole role) {
        UUID account = UUID.randomUUID();
        return anotherInstitution(new AssignmentStorageMembershipFixture(account, null, user(account, role), 0), role);
    }

    private static AssignmentStorageMembershipFixture anotherInstitution(AssignmentStorageMembershipFixture account, UserRole role) {
        UUID institution = UUID.randomUUID();
        long org = jdbc.queryForObject("insert into orgs(public_id,name) values(?,'Synthetic institution') returning id", Long.class, institution);
        jdbc.update("insert into user_org_roles(user_id,org_id,role) values(?,?,?::user_role)", account.userId(), org, role.name());
        return new AssignmentStorageMembershipFixture(account.account(), institution, account.userId(), org);
    }

    private static long user(UUID account, UserRole role) {
        return jdbc.queryForObject("insert into users(public_id,email,name,role,status) values(?,?,'Synthetic staff',?::user_role,'ACTIVE') returning id",
                Long.class, account, account + "@example.invalid", role.name());
    }

    private static UUID workspace() {
        return jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT','Synthetic workspace') returning public_id", UUID.class);
    }

    private static void raw(AssignmentStorageMembershipFixture membership, String mode, String payload, long revision) {
        jdbc.update("update user_org_roles set permission_mode=?,permission_payload=?::jsonb,permission_revision=? where user_id=? and org_id=?",
                mode, payload, revision, membership.userId(), membership.orgId());
    }

    private static void assertReason(Runnable action, InstitutionAssignmentStorageException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InstitutionAssignmentStorageException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private record AssignmentStorageMembershipFixture(UUID account, UUID institution, long userId, long orgId) { }

}
