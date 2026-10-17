package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.admin.AdminService;
import kr.ac.pusan.pickle.admin.AdminUserService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.support.TerminationFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RequestRecipientRetryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired RequestRepository requests;
    @Autowired WorkspaceRepository workspaces;
    @Autowired RequestRecipientService recipients;
    @Autowired RequestRecipientMaterializer materializer;
    @Autowired AdminService administration;
    @Autowired AdminUserService accounts;

    private User owner;
    private User writer;
    private User administrator;
    private long org;
    private long workspace;
    private long request;
    private long recipient;
    private long materializerJobsBefore;

    @BeforeEach
    void setup() {
        org = SeedFixtures.seedOrgId(jdbc);
        owner = user(UserRole.USER);
        writer = user(UserRole.ORG_MANAGER);
        administrator = user(UserRole.SYS_ADMIN);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",
                Long.class, "재시도 합성 " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner.getId());
        request = RequestFixtures.insertLlmKeyRequest(jdbc, workspace, org, owner.getId(), "재시도 합성 신청");
        jdbc.update("update requests set status='APPROVED' where id=?", request);
        jdbc.update("update llm_key_request_details set granted_passthrough_endpoints='[\"chat\"]' where request_id=?", request);
        jdbc.update("insert into request_reviews(request_id,reviewer_id,decision) values(?,?,'APPROVE')", request, owner.getId());
        recipient = jdbc.queryForObject("""
                insert into request_recipients(public_id,request_id,user_id,status,reason,attempts)
                values(?,?,?,'FAILED','preserved creation failure',3) returning id
                """, Long.class, UUID.randomUUID(), request, owner.getId());
        materializerJobsBefore = materializerJobs();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void retryRetainsItsExistingWriterRoleSet(UserRole role) throws Exception {
        User actor = user(role);
        boolean allowed = List.of(UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_ADMIN).contains(role);
        retry(actor).andExpect(allowed ? status().isOk() : status().isForbidden());
        assertThat(state()).isEqualTo(allowed ? "QUEUED" : "FAILED");
        assertThat(audits()).isEqualTo(allowed ? 1 : 0);
    }

    @Test
    void anotherInstitutionsWriterAndAnActualOwnerDoNotGainRetryAuthority() throws Exception {
        long otherOrg = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "재시도 외부 " + UUID.randomUUID());
        jdbc.update("delete from user_org_roles where user_id=?", writer.getId());
        SeedFixtures.grantOrgRole(jdbc, writer.getId(), otherOrg, UserRole.ORG_MANAGER);
        SeedFixtures.grantOrgRole(jdbc, writer.getId(), org, UserRole.ORG_VIEWER);
        retry(writer).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        retry(owner).andExpect(status().isForbidden());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_REVOKED", "ORG_REVOKED", "ACCOUNT_DISABLED"})
    void authorityChangesCommittedWhileTheHttpRetryWaitsTakeEffect(String change) throws Exception {
        if ("ORG_REVOKED".equals(change)) {
            long otherOrg = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "재시도 유지 기관 " + UUID.randomUUID());
            SeedFixtures.grantOrgRole(jdbc, writer.getId(), otherOrg, UserRole.ORG_MANAGER);
        }
        int expected = "ORG_REVOKED".equals(change) ? 404 : "ACCOUNT_DISABLED".equals(change) ? 401 : 403;
        String code = expected == 404 ? "RESOURCE_NOT_FOUND" : expected == 401 ? "AUTH_TOKEN_INVALID" : "ACCESS_DENIED";
        String capturedToken = tokens.createAccessToken(writer);
        var pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> response = new AtomicReference<>();
        try {
            tx.executeWithoutResult(transaction -> {
                administrationLock();
                response.set(pool.submit(() -> {
                    retry(capturedToken).andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code))
                            .andExpect(jsonPath("$.recipients").doesNotExist()).andExpect(jsonPath("$.workspaceId").doesNotExist());
                    return null;
                }));
                awaitAdministrationWait();
                if ("ACCOUNT_DISABLED".equals(change)) {
                    accounts.disable(actor(administrator), writer.getPublicId(), "격리 테스트 비활성화", "127.0.0.1");
                } else {
                    long revision = jdbc.queryForObject("select admin_revision from orgs where id=?", Long.class, org);
                    administration.revokeOrgRole(actor(administrator), writer.getPublicId(), pub("orgs", org), revision, "127.0.0.1");
                }
            });
            response.get().get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertUnchanged();
    }

    @Test
    void aCachedWorkspaceMustBeRefreshedAfterItsDeletionCommits() throws Exception {
        var delete = TerminationFixtures.delete(mvc, json, tokens.createAccessToken(owner), workspacePath());
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(transaction -> {
                var cached = workspaces.findById(workspace).orElseThrow();
                assertThat(cached.isDeleted()).isFalse();
                try {
                    pool.submit(() -> { mvc.perform(delete).andExpect(status().is2xxSuccessful()); return null; }).get(15, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                assertThat(cached.isDeleted()).isFalse();
                assertThatThrownBy(() -> recipients.retry(requests.findById(request).orElseThrow(), pub("request_recipients", recipient)))
                        .hasMessageContaining("REQUEST_RECIPIENT_NOT_RETRYABLE");
            });
        } finally { pool.shutdownNow(); }
        assertUnchanged();
    }

    @Test
    void aRetryWaitingForTheWorkspaceRowObservesTheCommittedDeletion() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> response = new AtomicReference<>();
        try {
            tx.executeWithoutResult(transaction -> {
                jdbc.queryForObject("select id from workspaces where id=? for update", Long.class, workspace);
                response.set(pool.submit(() -> {
                    retry(writer).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_RECIPIENT_NOT_RETRYABLE"));
                    return null;
                }));
                awaitWorkspaceWait();
                jdbc.update("update workspaces set deleted_at=now(),deleted_by=? where id=?", owner.getId(), workspace);
            });
            response.get().get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertUnchanged();
    }

    @Test
    void aCommittedDeletionWinsAgainstTheHttpRetryWaitingForIt() throws Exception {
        var delete = TerminationFixtures.delete(mvc, json, tokens.createAccessToken(owner), workspacePath());
        var pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> response = new AtomicReference<>();
        try {
            tx.executeWithoutResult(transaction -> {
                try { mvc.perform(delete).andExpect(status().is2xxSuccessful()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                response.set(pool.submit(() -> {
                    retry(writer).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_RECIPIENT_NOT_RETRYABLE"));
                    return null;
                }));
                awaitAdministrationWait();
            });
            response.get().get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertUnchanged();
    }

    @Test
    void aRetryThatCommitsFirstRequiresTheDeletionToReconfirmAndThenClosesNormally() throws Exception {
        var staleDelete = TerminationFixtures.delete(mvc, json, tokens.createAccessToken(owner), workspacePath());
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var retry = pool.submit(() -> tx.executeWithoutResult(transaction -> {
                try { retry(writer).andExpect(status().isOk()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                queued.countDown();
                await(release);
            }));
            assertThat(queued.await(10, TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> {
                mvc.perform(staleDelete).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TERMINATION_IMPACT_CHANGED"));
                return null;
            });
            awaitAdministrationWait();
            release.countDown();
            retry.get(15, TimeUnit.SECONDS);
            deletion.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(state()).isEqualTo("QUEUED");
        assertThat(audits()).isEqualTo(1);
        mvc.perform(TerminationFixtures.delete(mvc, json, tokens.createAccessToken(owner), workspacePath()))
                .andExpect(status().is2xxSuccessful());
        materializer.run();
        assertThat(state()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select count(*) from llm_api_keys where request_id=?", Long.class, request)).isZero();
        assertThat(materializerJobs()).isEqualTo(materializerJobsBefore + 1);
        assertThat(jdbc.queryForObject("select status from requests where id=?", String.class, request)).isEqualTo("APPROVED");
        assertThat(audits()).isEqualTo(1);
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForMap("select status,reason,attempts from request_recipients where id=?", recipient))
                .containsEntry("status", "FAILED").containsEntry("reason", "preserved creation failure").containsEntry("attempts", 3);
        assertThat(jdbc.queryForObject("select status from requests where id=?", String.class, request)).isEqualTo("APPROVED");
        assertThat(audits()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from llm_api_keys where request_id=?", Long.class, request)).isZero();
        assertThat(materializerJobs()).isEqualTo(materializerJobsBefore);
    }
    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@pusan.ac.kr", "unused", "재시도 합성 사용자");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user = users.saveAndFlush(user);
        if (role.isOrgTier()) SeedFixtures.grantOrgRole(jdbc, user.getId(), org, role);
        return user;
    }
    private AuthenticatedUser actor(User user) { return new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), user.getRole(), Map.of()); }
    private String state() { return jdbc.queryForObject("select status from request_recipients where id=?", String.class, recipient); }
    private long audits() { return jdbc.queryForObject("select count(*) from audit_logs where action='request.recipient_retry' and target_id=?", Long.class, pub("requests", request).toString()); }
    private long materializerJobs() { return jdbc.queryForObject("select count(*) from jobrunr_jobs where jobsignature like '%RequestRecipientMaterializer.run(%'", Long.class); }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private String workspacePath() { return "/api/v1/workspaces/" + pub("workspaces", workspace); }
    private ResultActions retry(User user) throws Exception { return retry(tokens.createAccessToken(user)); }
    private ResultActions retry(String token) throws Exception {
        return mvc.perform(post("/api/v1/admin/requests/" + pub("requests", request) + "/recipients/" + pub("request_recipients", recipient) + "/retry")
                .header("Authorization", "Bearer " + token));
    }
    private void administrationLock() { jdbc.queryForObject("select pg_advisory_xact_lock(1129074511,1)::text", String.class); }
    private void awaitAdministrationWait() {
        awaitLock("select count(*) from pg_locks where locktype='advisory' and classid=1129074511 and objid=1 and not granted");
    }
    private void awaitWorkspaceWait() {
        awaitLock("select count(*) from pg_locks where not granted and pg_backend_pid()=any(pg_blocking_pids(pid))");
    }
    private void awaitLock(String query) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(query, Long.class) == 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("HTTP retry did not reach the expected database lock");
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
    }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
