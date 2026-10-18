package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.config.JwtProperties;
import kr.ac.pusan.pickle.config.MfaProperties;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtAuthenticationFilter;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic identity/source integration only; no operating database or final endpoint guards. */
class InstitutionActorProviderTest {
    private static final String SIGNING_KEY = "synthetic-signing-key-at-least-thirty-two-bytes";
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate transactions;
    private static InstitutionActorProvider provider;
    private static OrgAdministrationLock barrier;
    private static InstitutionAssignmentStore store;

    @BeforeAll
    static void syntheticDatabase() throws Exception {
        postgres = EmbeddedPostgres.start(); dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("142").load().migrate();
        provider = new InstitutionActorProvider(jdbc, manager); barrier = new OrgAdministrationLock(jdbc);
        store = new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec());
    }

    @AfterAll
    static void closeDatabase() throws Exception { if (postgres != null) { postgres.close(); } }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void userAndSystemRolesHaveValidEmptyInstitutionContextWithoutImpersonation() {
        for (UserRole role : List.of(UserRole.USER, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            InstitutionActorFixture fixture = account(role);
            AuthenticatedUser actor = authenticate(fixture);
            assertThat(actor.role()).isEqualTo(role);
            assertThat(actor.authenticatedTokenVersion()).isZero();
            assertThat(actor.institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_EMPTY);
            assertThat(actor.institutionContext().authority()).isPresent();
            assertThat(actor.institutionContext().authority().orElseThrow().assignments()).isEmpty();
        }
    }

    @Test
    void validSourcesPreserveRowwiseProvenanceWithoutDelegationFromADisplayRole() {
        InstitutionActorFixture fixture = account(UserRole.ORG_ADMIN);
        UUID fullOrg = institution(); UUID selectedOrg = institution();
        grant(fixture, fullOrg, true, InstitutionWork.NOTICE_READ);
        grant(fixture, selectedOrg, false, InstitutionWork.VM_POWER);
        AuthenticatedUser actor = authenticate(fixture);
        InstitutionAuthority authority = actor.institutionContext().authority().orElseThrow();
        assertThat(authority.assignments()).hasSize(2);
        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(selectedOrg, InstitutionWorkGroup.VM, UUID.randomUUID())).sources())
                .extracting(InstitutionWorkDecision.GrantSource::institutionId).containsExactly(selectedOrg);
        assertThat(authority.evaluate(InstitutionWork.INSTITUTION_DELEGATE, InstitutionWorkTarget.institution(fullOrg)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.INSTITUTION_DELEGATE, InstitutionWorkTarget.institution(selectedOrg)).allowed()).isFalse();
    }

    @Test
    void oneUnconvertedSourceInvalidatesTheWholeInstitutionContextButKeepsIdentity() {
        InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER);
        grant(fixture, institution(), false, InstitutionWork.VM_POWER);
        UUID unconverted = institution(); membership(fixture, unconverted, UserRole.ORG_MANAGER);
        AuthenticatedUser actor = authenticate(fixture);
        assertThat(actor.id()).isEqualTo(fixture.id());
        assertThat(actor.role()).isEqualTo(UserRole.ORG_MANAGER);
        assertInvalid(actor, InstitutionActorContext.Failure.UNCONVERTED);
        assertThat(jdbc.queryForObject("select permission_mode from user_org_roles where user_id=? and org_id=?",
                String.class, fixture.id(), orgId(unconverted))).isNull();
    }

    @Test
    void unknownPayloadAndRowRoleMismatchRemainExplicitInvalidContexts() {
        InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER); UUID org = institution();
        grant(fixture, org, false, InstitutionWork.VM_POWER);
        jdbc.update("update user_org_roles set permission_payload='{\"work\":[\"unknown.work\"],\"scopes\":{}}'::jsonb where user_id=?", fixture.id());
        assertInvalid(authenticate(fixture), InstitutionActorContext.Failure.CORRUPT_PAYLOAD);
        jdbc.update("update user_org_roles set permission_payload='{\"work\":[\"notice.read\"],\"scopes\":{}}'::jsonb where user_id=?", fixture.id());
        assertInvalid(authenticate(fixture), InstitutionActorContext.Failure.ROLE_MISMATCH);
    }

    @Test
    void roleAggregateMismatchAndUnexpectedInstitutionRowsDoNotPromoteAnyone() {
        InstitutionActorFixture orgAccount = account(UserRole.ORG_ADMIN);
        grant(orgAccount, institution(), false, InstitutionWork.VM_POWER);
        assertInvalid(authenticate(orgAccount), InstitutionActorContext.Failure.ACCOUNT_ROLE_MISMATCH);
        assertInvalid(authenticate(account(UserRole.ORG_VIEWER)), InstitutionActorContext.Failure.ACCOUNT_ROLE_MISMATCH);
        for (UserRole role : List.of(UserRole.USER, UserRole.SYS_ADMIN)) {
            InstitutionActorFixture fixture = account(role); grant(fixture, institution(), true, InstitutionWork.NOTICE_READ);
            AuthenticatedUser actor = authenticate(fixture);
            assertThat(actor.role()).isEqualTo(role);
            assertInvalid(actor, InstitutionActorContext.Failure.ACCOUNT_ROLE_MISMATCH);
        }
    }

    @Test
    void sameDisplayRoleReceivesChangedWorkAndFingerprintOnTheNextRead() {
        InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER); UUID org = institution();
        InstitutionAssignment first = grant(fixture, org, false, InstitutionWork.VM_POWER);
        AuthenticatedUser before = authenticate(fixture);
        transactions.execute(status -> store.save(selected(fixture, org, InstitutionWork.LLM_TOKEN_OPERATE), first.revision()));
        AuthenticatedUser after = provider.reloadCurrent(before);
        assertThat(after.role()).isEqualTo(before.role());
        assertThat(after.institutionContext().authority().orElseThrow().fingerprint())
                .isNotEqualTo(before.institutionContext().authority().orElseThrow().fingerprint());
        assertThat(after.institutionContext().authority().orElseThrow().evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(org, InstitutionWorkGroup.VM, UUID.randomUUID())).allowed()).isFalse();
    }

    @Test
    void tokenRevocationAndInactiveStatusRejectFreshAuthenticationAndPostLockReload() {
        InstitutionActorFixture fixture = account(UserRole.USER); AuthenticatedUser actor = authenticate(fixture);
        jdbc.update("update users set token_version=token_version+1 where id=?", fixture.id());
        assertThat(provider.authenticate(fixture.publicId(), 0)).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> { barrier.acquire(); return provider.reloadAfterAdministrationLock(actor); }))
                .isInstanceOf(ApiException.class);
        AuthenticatedUser renewed = provider.authenticate(fixture.publicId(), 1).orElseThrow();
        jdbc.update("update users set status='DISABLED' where id=?", fixture.id());
        assertThat(provider.authenticate(fixture.publicId(), 1)).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> { barrier.acquire(); return provider.reloadAfterAdministrationLock(renewed); }))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingOriginalBearerVersionNeverInterpolatesTheCurrentDatabaseVersion() {
        InstitutionActorFixture fixture = account(UserRole.USER);
        AuthenticatedUser direct = new AuthenticatedUser(fixture.id(), fixture.publicId(), "synthetic@example.invalid", UserRole.USER, Map.of());
        assertInvalid(direct, InstitutionActorContext.Failure.NOT_LOADED);
        assertThatThrownBy(() -> provider.reloadCurrent(direct)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transactions.execute(status -> { barrier.acquire(); return provider.reloadAfterAdministrationLock(direct); }))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void postLockEntryRequiresItsOwnTransactionAndActualBarrierInsteadOfAnyActiveTransaction() {
        AuthenticatedUser actor = authenticate(account(UserRole.USER));
        assertThatThrownBy(() -> provider.reloadAfterAdministrationLock(actor)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.execute(status -> provider.reloadAfterAdministrationLock(actor))).isInstanceOf(IllegalStateException.class);
        TransactionTemplate oldSnapshot = new TransactionTemplate(manager);
        oldSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> oldSnapshot.execute(status -> { barrier.acquire(); return provider.reloadAfterAdministrationLock(actor); }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sharedAdvisoryLockDoesNotSatisfyTheExclusiveAdministrationBarrier() {
        AuthenticatedUser actor = authenticate(account(UserRole.USER));
        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock_shared(1129074511,1)");
            return provider.reloadAfterAdministrationLock(actor);
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postLockReloadUsesTheCallerConnectionAndDoesNotCommitItsChanges() {
        InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER); UUID org = institution();
        InstitutionAssignment before = grant(fixture, org, false, InstitutionWork.VM_POWER);
        AuthenticatedUser original = authenticate(fixture);
        transactions.execute(status -> {
            barrier.acquire();
            InstitutionAssignment changed = store.save(selected(fixture, org, InstitutionWork.LLM_TOKEN_OPERATE), before.revision());
            AuthenticatedUser reloaded = provider.reloadAfterAdministrationLock(original);
            assertThat(reloaded.institutionContext().authority().orElseThrow().assignments()).containsExactly(changed);
            status.setRollbackOnly(); return null;
        });
        assertThat(store.load(fixture.publicId()).assignments()).containsExactly(before);
    }

    @Test
    void anotherDatasourceTransactionCannotServeAsTheProvidersSnapshotOrWriteBarrier() {
        AuthenticatedUser actor = authenticate(account(UserRole.USER));
        var otherManager = new DataSourceTransactionManager(new DelegatingDataSource(dataSource));
        InstitutionActorProvider wrong = new InstitutionActorProvider(jdbc, otherManager);
        assertThatThrownBy(() -> wrong.authenticate(actor.publicId(), 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(otherManager).execute(status -> {
            jdbc.queryForObject("select 1", Integer.class);
            return provider.reloadAfterAdministrationLock(actor);
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theApplicationStyleJpaManagerOwnsBothFreshSnapshotAndPostLockRollback() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource); factory.setPersistenceUnitName("synthetic-institution-actor");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.setManagedTypes(PersistenceManagedTypes.of());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var jpaManager = new JpaTransactionManager(factory.getObject()); jpaManager.setDataSource(dataSource);
            InstitutionActorProvider jpaProvider = new InstitutionActorProvider(jdbc, jpaManager);
            InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER); UUID org = institution();
            InstitutionAssignment before = grant(fixture, org, false, InstitutionWork.VM_POWER);
            AuthenticatedUser original = jpaProvider.authenticate(fixture.publicId(), 0).orElseThrow();
            new TransactionTemplate(jpaManager).execute(status -> {
                barrier.acquire();
                InstitutionAssignment changed = store.save(selected(fixture, org, InstitutionWork.LLM_TOKEN_OPERATE), before.revision());
                assertThat(jpaProvider.reloadAfterAdministrationLock(original).institutionContext().authority().orElseThrow().assignments())
                        .containsExactly(changed);
                status.setRollbackOnly(); return null;
            });
            assertThat(store.load(fixture.publicId()).assignments()).containsExactly(before);
        } finally { factory.destroy(); }
    }

    @Test
    void waitingCommandReadsSourceRevocationAfterTheAdministrationBarrier() throws Exception {
        InstitutionActorFixture fixture = account(UserRole.ORG_MANAGER); UUID org = institution(); grant(fixture, org, false, InstitutionWork.VM_POWER);
        AuthenticatedUser original = authenticate(fixture); CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = transactions.execute(status -> {
                barrier.acquire();
                var pending = executor.submit(() -> transactions.execute(other -> { started.countDown(); barrier.acquire(); return provider.reloadAfterAdministrationLock(original); }));
                await(started);
                jdbc.update("delete from user_org_roles where user_id=?", fixture.id());
                jdbc.update("update users set role='USER' where id=?", fixture.id());
                return pending;
            });
            AuthenticatedUser after = future.get(10, TimeUnit.SECONDS);
            assertThat(after.role()).isEqualTo(UserRole.USER);
            assertThat(after.institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_EMPTY);
            assertThat(original.institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_SOURCES);
        }
    }

    @Test
    void identityAndSourcesCannotComeFromDifferentCommittedSnapshots() throws Exception {
        InstitutionActorFixture fixture = account(UserRole.ORG_ADMIN); UUID org = institution(); grant(fixture, org, true, InstitutionWork.NOTICE_READ);
        CountDownLatch identityRead = new CountDownLatch(1); CountDownLatch changed = new CountDownLatch(1);
        JdbcTemplate paused = new InstitutionPausingJdbcTemplate(dataSource, identityRead, changed);
        InstitutionActorProvider isolated = new InstitutionActorProvider(paused, manager);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> isolated.authenticate(fixture.publicId(), 0).orElseThrow());
            await(identityRead);
            transactions.execute(status -> {
                jdbc.update("update users set role='ORG_VIEWER' where id=?", fixture.id());
                jdbc.update("update user_org_roles set role='ORG_VIEWER',permission_mode='SELECTED',permission_payload='{\"work\":[\"notice.read\"],\"scopes\":{}}'::jsonb,permission_revision=nextval('institution_permission_revision_seq') where user_id=?", fixture.id());
                return null;
            });
            changed.countDown();
            AuthenticatedUser old = pending.get(10, TimeUnit.SECONDS);
            assertThat(old.role()).isEqualTo(UserRole.ORG_ADMIN);
            assertThat(old.institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_SOURCES);
            assertThat(old.institutionContext().authority().orElseThrow().assignments().getFirst().mode()).isEqualTo(InstitutionAssignment.Mode.FULL);
            assertThat(authenticate(fixture).role()).isEqualTo(UserRole.ORG_VIEWER);
        } finally { changed.countDown(); }
    }

    @Test
    void jwtRoleClaimIsDisplayOnlyWhileCurrentDatabaseIdentityAndContextAreUsed() throws Exception {
        InstitutionActorFixture fixture = account(UserRole.ORG_VIEWER); grant(fixture, institution(), false, InstitutionWork.NOTICE_READ);
        String token = Jwts.builder().subject(fixture.publicId().toString()).claim(JwtService.CLAIM_ROLE, "SYS_ADMIN")
                .claim(JwtService.CLAIM_TOKEN_VERSION, 0).expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8))).compact();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtService(new JwtProperties(SIGNING_KEY, Duration.ofMinutes(15))), provider);
        MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ORG_VIEWER");
        assertThat(((AuthenticatedUser) authentication.getPrincipal()).institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_SOURCES);
    }

    @Test
    void lifecycleReadPreviewWorksInsideReadOnlySnapshotAndSysMfaBoundaryRemainsSeparate() {
        MfaService mfa = new MfaService(null, null, null, null, null, null, null, null, null, null) {
            @Override public boolean isEnrolled(long userId) { return false; }
        };
        LifecycleActors actors = new LifecycleActors(provider, new MfaProperties(true), mfa, org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class));
        AuthenticatedUser user = authenticate(account(UserRole.USER));
        TransactionTemplate preview = new TransactionTemplate(manager); preview.setReadOnly(true);
        preview.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(preview.execute(status -> actors.current(user)).id()).isEqualTo(user.id());
        AuthenticatedUser org = authenticate(account(UserRole.ORG_VIEWER));
        assertThat(actors.current(org).role()).isEqualTo(UserRole.ORG_VIEWER);
        AuthenticatedUser sys = authenticate(account(UserRole.SYS_VIEWER));
        assertThatThrownBy(() -> actors.current(sys)).isInstanceOf(ApiException.class);
    }

    private static InstitutionActorFixture account(UserRole role) {
        UUID id = UUID.randomUUID();
        long internal = jdbc.queryForObject("insert into users(public_id,email,name,role,status) values(?,?,'Synthetic actor',?::user_role,'ACTIVE') returning id",
                Long.class, id, id + "@example.invalid", role.name());
        return new InstitutionActorFixture(internal, id);
    }
    private static UUID institution() {
        return jdbc.queryForObject("insert into orgs(name) values('Synthetic institution') returning public_id", UUID.class);
    }
    private static long orgId(UUID publicId) { return jdbc.queryForObject("select id from orgs where public_id=?", Long.class, publicId); }
    private static void membership(InstitutionActorFixture fixture, UUID org, UserRole role) {
        jdbc.update("insert into user_org_roles(user_id,org_id,role) values(?,?,?::user_role)", fixture.id(), orgId(org), role.name());
    }
    private static InstitutionAssignment grant(InstitutionActorFixture fixture, UUID org, boolean full, InstitutionWork work) {
        membership(fixture, org, full ? UserRole.ORG_ADMIN : (work.write() ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER));
        return transactions.execute(status -> store.save(full ? InstitutionAssignment.full(fixture.publicId(), org, 0) : selected(fixture, org, work), 0));
    }
    private static InstitutionAssignment selected(InstitutionActorFixture fixture, UUID org, InstitutionWork work) {
        return InstitutionAssignment.selected(fixture.publicId(), org, Set.of(work), work.group() == null ? Map.of()
                : Map.of(work.group(), InstitutionWorkspaceScope.all()), 0);
    }
    private static AuthenticatedUser authenticate(InstitutionActorFixture fixture) { return provider.authenticate(fixture.publicId(), 0).orElseThrow(); }
    private static void assertInvalid(AuthenticatedUser actor, InstitutionActorContext.Failure failure) {
        assertThat(actor.institutionContext().state()).isEqualTo(InstitutionActorContext.State.INVALID);
        assertThat(actor.institutionContext().failure()).contains(failure);
        assertThat(actor.institutionContext().authority()).isEmpty();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Synthetic concurrency timeout"); } }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private record InstitutionActorFixture(long id, UUID publicId) { }
    private static final class InstitutionPausingJdbcTemplate extends JdbcTemplate {
        private final CountDownLatch read;
        private final CountDownLatch changed;
        private final AtomicBoolean pause = new AtomicBoolean(true);
        private InstitutionPausingJdbcTemplate(DataSource source, CountDownLatch read, CountDownLatch changed) {
            super(source); this.read = read; this.changed = changed;
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            List<T> rows = super.query(sql, mapper, args);
            if (sql.contains("token_version from users") && pause.compareAndSet(true, false)) { read.countDown(); await(changed); }
            return rows;
        }
    }
}
