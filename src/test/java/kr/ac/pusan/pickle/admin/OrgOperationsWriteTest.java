package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.admin.dto.SaveOrgOperationsRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.notification.NotificationDispatchJob;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OrgOperationsWriteTest {
    @Autowired private MockMvc mvc;
    @Autowired private kr.ac.pusan.pickle.auth.AuthService auth;
    @Autowired private kr.ac.pusan.pickle.auth.EmailVerificationRepository verifications;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrgRepository orgRepository;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private NotificationService notifications;
    @Autowired private NotificationDispatchJob dispatcher;
    @Autowired private MockMailSender mail;
    @Autowired private AdminOrgOperationsService operations;
    @Autowired private AdminOrgOperationsQueryService query;
    @Autowired private kr.ac.pusan.pickle.orgs.OrgAccountStatusChanges accountStatusChanges;
    @MockitoSpyBean private AuditService audit;

    private Org org;
    private User admin;
    private User manager;
    private User viewer;
    private User sys;

    @BeforeEach
    void setUp() {
        org = orgRepository.saveAndFlush(new Org("원자 명단 " + UUID.randomUUID(), null));
        admin = user(UserRole.ORG_ADMIN);
        manager = user(UserRole.ORG_MANAGER);
        viewer = user(UserRole.ORG_VIEWER);
        sys = user(UserRole.SYS_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, admin.getId(), org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, manager.getId(), org.getId(), UserRole.ORG_MANAGER);
        SeedFixtures.grantOrgRole(jdbc, viewer.getId(), org.getId(), UserRole.ORG_VIEWER);
        jdbc.update("update notifications set status = 'SENT', sent_at = now() where status = 'PENDING'");
        mail.clear();
    }

    @Test
    void emptyDesignatedListWarnsAndSavesWithoutFallback() throws Exception {
        var body = settings(0, RequestMailMode.DESIGNATED, false, false);
        mvc.perform(post(path() + "/preview").header("Authorization", token(sys))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.currentMailRecipientCount").value(0))
                .andExpect(jsonPath("$.after.legacyFallback").value(false))
                .andExpect(jsonPath("$.warnings.length()").value(1));
        assertThat(revision()).isZero();
        save(body, sys).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(0));
        assertThat(audits()).isEqualTo(1);
        publishRequest();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id in (?, ?)"
                        + " and status = 'PENDING'", Integer.class, admin.getId(), manager.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id in (?, ?)"
                        + " and status = 'SKIPPED' and skip_reason = 'NOT_SELECTED'", Integer.class,
                admin.getId(), manager.getId())).isEqualTo(2);
    }

    @Test
    void oldEditorCannotOverwriteNewRosterOrCreateAnotherAudit() throws Exception {
        var body = settings(0, RequestMailMode.DESIGNATED, true, false);
        save(body, sys).andExpect(status().isOk());
        save(settings(0, RequestMailMode.ALL_APPROVERS, false, false), sys)
                .andExpect(status().isConflict());
        assertThat(revision()).isEqualTo(1);
        assertThat(audits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select request_mail_mode from orgs where id = ?",
                String.class, org.getId())).isEqualTo("DESIGNATED");
    }

    @Test
    void nullMembersAreRejectedBeforePreviewAndSave() throws Exception {
        String body = "{\"expectedRevision\":0,\"members\":[null],\"mailMode\":\"DESIGNATED\"}";
        mvc.perform(post(path() + "/preview").header("Authorization", token(sys))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put(path()).header("Authorization", token(sys))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertThat(revision()).isZero();
    }

    @Test
    void losingTheLastAdminNeedsSystemReasonAndTargetConfirmation() throws Exception {
        var members = List.of(assignment(admin, UserRole.ORG_MANAGER, false),
                assignment(manager, UserRole.ORG_MANAGER, false), assignment(viewer, UserRole.ORG_VIEWER, false));
        var normal = new SaveOrgOperationsRequest(0L, members, RequestMailMode.DESIGNATED, null, false, null);
        save(normal, sys).andExpect(status().isConflict());
        assertThat(revision()).isZero();
        var override = new SaveOrgOperationsRequest(0L, members, RequestMailMode.DESIGNATED,
                "격리 데이터의 담당 공백 확인", true, org.getPublicId());
        save(override, sys).andExpect(status().isOk()).andExpect(jsonPath("$.activeAdminCount").value(0));
        assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ORG_MANAGER);
    }

    @Test
    void singleRecipientChangeUsesTheSameRevisionBoundary() throws Exception {
        mvc.perform(put("/api/v1/admin/users/" + admin.getPublicId() + "/org-roles/"
                        + org.getPublicId() + "/request-mail").header("Authorization", token(sys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"expectedRevision\":0}"))
                .andExpect(status().isOk());
        save(settings(0, RequestMailMode.DESIGNATED, false, false), sys).andExpect(status().isConflict());
        assertThat(revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select request_mail from user_org_roles where user_id = ? and org_id = ?",
                Boolean.class, admin.getId(), org.getId())).isTrue();
    }

    @Test
    void auditFailureRollsBackRosterPolicyAndRevision() throws Exception {
        AuditService auditTarget = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("synthetic audit failure")).when(auditTarget)
                .recordOrgChange(any(), anyString(), anyString(), any(), anyString(), any(), any());
        save(settings(0, RequestMailMode.DESIGNATED, true, true), sys).andExpect(status().isInternalServerError());
        assertThat(revision()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_org_roles where org_id = ? and request_mail",
                Integer.class, org.getId())).isZero();
        assertThat(audits()).isZero();
    }

    @Test
    void institutionAuditFindsSystemActorByImmutableTarget() throws Exception {
        save(settings(0, RequestMailMode.DESIGNATED, true, false), sys).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit").param("targetOrgId", org.getPublicId().toString())
                        .header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetOrgId").value(org.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].detail.before.revision").value(0))
                .andExpect(jsonPath("$.content[0].detail.after.revision").value(1));
        mvc.perform(get("/api/v1/admin/audit").param("targetOrgId", org.getPublicId().toString())
                        .header("Authorization", token(viewer))).andExpect(status().isForbidden());
    }

    @Test
    void frozenRecipientKeepsAddressAfterRoleLossAndAccountSuspension() throws Exception {
        String address = admin.getEmail();
        publishRequest();
        var next = new SaveOrgOperationsRequest(0L,
                List.of(assignment(manager, UserRole.ORG_ADMIN, true), assignment(viewer, UserRole.ORG_VIEWER, false)),
                RequestMailMode.DESIGNATED, null, false, null);
        save(next, sys).andExpect(status().isOk());
        jdbc.update("update users set status = 'DISABLED', email = ? where id = ?",
                "new." + UUID.randomUUID() + "@pusan.ac.kr", admin.getId());
        dispatcher.dispatch();
        assertThat(mail.lastMessageTo(address)).isNotNull();
        assertThat(mail.lastMessageTo(manager.getEmail())).isNull();
        assertThat(jdbc.queryForObject("select mail_mode from request_notification_selections where org_id = ?",
                String.class, org.getId())).isEqualTo("LEGACY");
    }

    @Test
    void inactiveLegacyBundleDoesNotAbortDispatch() {
        jdbc.update("insert into notifications(user_id,event,title,body,bundle) values (?, 'request.submitted', 'legacy', 'legacy', true)", admin.getId());
        jdbc.update("update users set status = 'DISABLED' where id = ?", admin.getId());
        dispatcher.dispatch();
        assertThat(mail.getMessages()).isEmpty();
        assertThat(jdbc.queryForObject("select status::text from notifications where user_id = ?",
                String.class, admin.getId())).isEqualTo("SKIPPED");
    }

    @Test
    void bundlesPartitionDifferentFrozenAddressesForTheSameAccount() {
        String first = admin.getEmail();
        publishRequest();
        String second = "second." + UUID.randomUUID() + "@pusan.ac.kr";
        jdbc.update("update users set email = ? where id = ?", second, admin.getId());
        publishRequest();
        dispatcher.dispatch();
        assertThat(mail.getMessages().stream().filter(message -> message.to().equals(first)).count()).isEqualTo(1);
        assertThat(mail.getMessages().stream().filter(message -> message.to().equals(second)).count()).isEqualTo(1);
    }

    @Test
    void concurrentInstitutionsRecomputeTheSharedAccountsHighestRole() throws Exception {
        Org other = orgRepository.saveAndFlush(new Org("두번째 명단 " + UUID.randomUUID(), null));
        User otherAdmin = user(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, admin.getId(), other.getId(), UserRole.ORG_VIEWER);
        SeedFixtures.grantOrgRole(jdbc, otherAdmin.getId(), other.getId(), UserRole.ORG_ADMIN);
        var first = new SaveOrgOperationsRequest(0L,
                List.of(assignment(manager, UserRole.ORG_ADMIN, true), assignment(viewer, UserRole.ORG_VIEWER, false)),
                RequestMailMode.DESIGNATED, null, false, null);
        var second = new SaveOrgOperationsRequest(0L,
                List.of(assignment(admin, UserRole.ORG_MANAGER, true), assignment(otherAdmin, UserRole.ORG_ADMIN, false)),
                RequestMailMode.DESIGNATED, null, false, null);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> operations.save(principal(sys), org.getPublicId(), first, "127.0.0.1"));
            var b = threads.submit(() -> operations.save(principal(sys), other.getPublicId(), second, "127.0.0.1"));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ORG_MANAGER);
        assertThat(SeedFixtures.managedOrgIds(jdbc, admin.getId())).containsExactly(other.getId());
    }

    @Test
    void enqueueWaitsForAtomicRosterCommitAndNeverSeesTheIntermediateList() throws Exception {
        operations.save(principal(sys), org.getPublicId(), settings(0, RequestMailMode.DESIGNATED, true, false), "127.0.0.1");
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var writer = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                operations.save(principal(sys), org.getPublicId(), settings(1, RequestMailMode.DESIGNATED, false, true), "127.0.0.1");
                changed.countDown();
                await(release);
            }));
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            var beforeCommit = query.get(principal(sys), org.getPublicId(), null);
            assertThat(beforeCommit.revision()).isEqualTo(1);
            assertThat(beforeCommit.members().stream().filter(member -> member.userId().equals(admin.getPublicId()))
                    .findFirst().orElseThrow().currentMailRecipient()).isTrue();
            var submission = threads.submit(this::publishRequest);
            waitForLockWaiter();
            assertThat(submission.isDone()).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            submission.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("select policy_revision from request_notification_selections where org_id = ?",
                Long.class, org.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and status = 'PENDING'",
                Integer.class, manager.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and status = 'PENDING'",
                Integer.class, admin.getId())).isZero();
    }

    @Test
    void staleSystemPrincipalCannotUseSystemOverrideAfterAuthorityChanged() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AuthenticatedUser oldPrincipal = principal(sys);
        var body = new SaveOrgOperationsRequest(0L, List.of(), RequestMailMode.DESIGNATED,
                "격리 예외 대조", true, org.getPublicId());
        try (var threads = Executors.newFixedThreadPool(2)) {
            var owner = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(1129074511, 1)::text", String.class);
                held.countDown();
                await(release);
                jdbc.update("update users set role = 'ORG_ADMIN' where id = ?", sys.getId());
                SeedFixtures.grantOrgRole(jdbc, sys.getId(), org.getId(), UserRole.ORG_ADMIN);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var change = threads.submit(() -> operations.save(oldPrincipal, org.getPublicId(), body, "127.0.0.1"));
            waitForLockWaiter();
            release.countDown();
            owner.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        } finally { release.countDown(); }
        assertThat(revision()).isZero();
        assertThat(audits()).isZero();
    }

    @Test
    void staleProfileCannotRestoreRoleOrTokenVersionAfterRosterCommit() throws Exception {
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newSingleThreadExecutor()) {
            var profile = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                User stale = users.findById(admin.getId()).orElseThrow();
                loaded.countDown();
                await(release);
                stale.setName("stale profile name");
                users.flush();
            }));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            var body = new SaveOrgOperationsRequest(0L,
                    List.of(assignment(admin, UserRole.ORG_MANAGER, false), assignment(manager, UserRole.ORG_ADMIN, true),
                            assignment(viewer, UserRole.ORG_VIEWER, false)), RequestMailMode.DESIGNATED, null, false, null);
            operations.save(principal(sys), org.getPublicId(), body, "127.0.0.1");
            release.countDown();
            assertThatThrownBy(() -> profile.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        } finally { release.countDown(); }
        User stored = users.findById(admin.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(UserRole.ORG_MANAGER);
        assertThat(stored.getTokenVersion()).isEqualTo(1);
        assertThat(stored.getName()).isEqualTo(admin.getName());
    }

    @Test
    void voluntaryWithdrawalNeedsAnotherInstitutionAdministrator() {
        assertThatThrownBy(() -> accountStatusChanges.requireMayWithdraw(admin.getId()))
                .isInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        var body = new SaveOrgOperationsRequest(0L,
                List.of(assignment(admin, UserRole.ORG_ADMIN, false), assignment(manager, UserRole.ORG_ADMIN, true),
                        assignment(viewer, UserRole.ORG_VIEWER, false)), RequestMailMode.DESIGNATED, null, false, null);
        operations.save(principal(sys), org.getPublicId(), body, "127.0.0.1");
        accountStatusChanges.requireMayWithdraw(admin.getId());
        accountStatusChanges.requireMayWithdraw(viewer.getId());
    }

    @Test
    void emailActivationWaitsForRosterLockAndInvalidatesPendingEditors() throws Exception {
        admin.setStatus(UserStatus.PENDING_VERIFICATION);
        users.saveAndFlush(admin);
        String rawToken = kr.ac.pusan.pickle.auth.TokenHasher.newToken();
        verifications.saveAndFlush(new kr.ac.pusan.pickle.auth.EmailVerification(admin.getId(),
                kr.ac.pusan.pickle.auth.TokenHasher.sha256Hex(rawToken),
                kr.ac.pusan.pickle.auth.VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300)));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var owner = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(1129074511, 1)::text", String.class);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var activation = threads.submit(() -> auth.verifyEmail(rawToken, "127.0.0.61"));
            waitForLockWaiter();
            assertThat(users.findById(admin.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.PENDING_VERIFICATION);
            release.countDown();
            owner.get(10, TimeUnit.SECONDS);
            activation.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(revision()).isEqualTo(1);
        assertThat(users.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_org_id = ?"
                + " and action = 'org.staff_status_update'", Integer.class, org.getId())).isEqualTo(1);
        save(settings(0, RequestMailMode.DESIGNATED, false, false), sys).andExpect(status().isConflict());
    }

    @Test
    void emailVerificationDoesNotLockTokenBeforeWaitingForAdministration() throws Exception {
        admin.setStatus(UserStatus.PENDING_VERIFICATION);
        users.saveAndFlush(admin);
        String rawToken = kr.ac.pusan.pickle.auth.TokenHasher.newToken();
        var verification = verifications.saveAndFlush(new kr.ac.pusan.pickle.auth.EmailVerification(admin.getId(),
                kr.ac.pusan.pickle.auth.TokenHasher.sha256Hex(rawToken),
                kr.ac.pusan.pickle.auth.VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300)));
        var held = new CountDownLatch(1);
        var invalidate = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var googleInvalidation = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(1129074511, 1)::text", String.class);
                jdbc.execute("set local lock_timeout = '2s'");
                held.countDown();
                await(invalidate);
                jdbc.update("update email_verifications set used_at = now() where id = ?", verification.getId());
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var activation = threads.submit(() -> auth.verifyEmail(rawToken, "127.0.0.62"));
            waitForLockWaiter();
            invalidate.countDown();
            googleInvalidation.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> activation.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        } finally { invalidate.countDown(); }
        assertThat(revision()).isZero();
        assertThat(users.findById(admin.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    private void waitForLockWaiter() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            int count = jdbc.queryForObject("select count(*) from pg_locks where locktype = 'advisory'"
                    + " and classid = 1129074511 and objid = 1 and not granted", Integer.class);
            if (count > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("recipient/mutation transaction did not wait for the shared advisory lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), user.getRole(), Map.of());
    }

    private void publishRequest() {
        User requester = user(UserRole.USER);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            long workspace = jdbc.queryForObject("insert into workspaces(kind,name) values ('PROJECT', ?) returning id",
                    Long.class, "메일 격리 " + UUID.randomUUID());
            long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
            long id = RequestFixtures.insertVmRequest(jdbc, workspace, org.getId(), requester.getId(), "격리 신청", image);
            UUID publicId = SeedFixtures.publicId(jdbc, "requests", id);
            notifications.publishRequestSubmitted(id, publicId, org.getPublicId(), requester.getId(),
                    Map.of("requestId", publicId, "workspaceName", "격리", "purpose", "격리", "type", "VM"));
        });
    }

    private SaveOrgOperationsRequest settings(long revision, RequestMailMode mode, boolean adminMail, boolean managerMail) {
        return new SaveOrgOperationsRequest(revision,
                List.of(assignment(admin, UserRole.ORG_ADMIN, adminMail), assignment(manager, UserRole.ORG_MANAGER, managerMail),
                        assignment(viewer, UserRole.ORG_VIEWER, false)), mode, null, false, null);
    }

    private static SaveOrgOperationsRequest.Assignment assignment(User user, UserRole role, boolean mail) {
        return new SaveOrgOperationsRequest.Assignment(user.getPublicId(), role, mail);
    }

    private ResultActions save(SaveOrgOperationsRequest body, User actor) throws Exception {
        return mvc.perform(put(path()).header("Authorization", token(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private long revision() {
        return jdbc.queryForObject("select admin_revision from orgs where id = ?", Long.class, org.getId());
    }

    private int audits() {
        return jdbc.queryForObject("select count(*) from audit_logs where target_org_id = ?", Integer.class, org.getId());
    }

    private String path() { return "/api/v1/admin/orgs/" + org.getPublicId() + "/operations"; }
    private String token(User user) { return "Bearer " + jwt.createAccessToken(user); }

    private User user(UserRole role) {
        User user = new User("ow." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "격리 " + role);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }
}
