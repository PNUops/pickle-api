package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.PickleApiApplication;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.publishing.DomainVerificationJob;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.UserOrgRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Committed results survive retries without repeating writes or job registration. */
@SpringBootTest(properties = {"jobrunr.background-job-server.enabled=false", "jobrunr.dashboard.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminBulkChangeDurabilityTest {
    private static final String PREVIEW = "/api/v1/admin/bulk-changes/preview";
    private static final String APPLY = "/api/v1/admin/bulk-changes";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @Autowired private OrgRepository orgs;
    @Autowired private UserOrgRoleService orgRoles;
    @Autowired private CredentialCipher cipher;
    @MockitoSpyBean private Clock clock;
    static volatile DataSource restartDatabase;
    private long org;
    private long workspace;
    private User actor;
    private String token;

    @BeforeEach
    void fixtures() {
        org = SeedFixtures.seedOrgId(jdbc);
        String suffix = UUID.randomUUID().toString();
        actor = new User("durable-" + suffix + "@pusan.ac.kr", "{test-no-login}", "합성 관리자");
        actor.setRole(UserRole.SYS_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setEmailVerifiedAt(Instant.now());
        actor = users.save(actor);
        token = jwt.createAccessToken(actor);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",
                Long.class, "합성 결과 " + suffix);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')",
                workspace, actor.getId());
    }

    @Test
    void responseLossRetryReturnsOriginalResultWithoutRegisteringVerificationAgain() throws Exception {
        long domain = customDomain();
        UUID publicId = pub("domains", domain);
        Map<String, Object> body = prepared("DOMAIN", List.of(publicId),
                Map.of("kind", "DOMAIN_VERIFY", "domainVerify", Map.of()));
        long jobsBefore = verificationJobs();
        JsonNode committed = apply(body);
        assertThat(item(committed, publicId).get("result").asString()).isEqualTo("APPLIED");
        assertThat(verificationJobs()).as("actual after-commit registration").isEqualTo(jobsBefore + 1);
        assertThat(targetAudits(publicId, "domain.admin_verify")).isEqualTo(1);
        JsonNode retry = apply(body);
        databaseProof("verification-retry", publicId, committed, retry);
        assertAll(
                () -> assertThat(retry.get("batchId")).isEqualTo(committed.get("batchId")),
                () -> assertThat(retry.get("items")).isEqualTo(committed.get("items")),
                () -> assertThat(verificationJobs()).as("lost response must not enqueue twice").isEqualTo(jobsBefore + 1),
                () -> assertThat(targetAudits(publicId, "domain.admin_verify")).isEqualTo(1),
                () -> assertThat(summaryAudits()).isEqualTo(1));
        assertThat(jdbc.queryForObject("select status::text from domains where id=?", String.class, domain))
                .as("a saved acceptance and a queued job do not establish verification completion").isEqualTo("PENDING");
    }

    @Test
    void retryAfterCommittedLimitsChangePreservesAppliedResultAndGeneration() throws Exception {
        long key = localKey();
        seedOtherLimits(key);
        UUID publicId = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(publicId),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 91)));
        long before = generation();
        JsonNode committed = apply(body);
        assertThat(item(committed, publicId).get("result").asString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, key)).isEqualTo(91);
        assertOtherLimits(key);
        assertThat(jdbc.queryForObject("select jsonb_exists(request->'change'->'llmKeyLimits','tpm') from admin_bulk_change_previews where id=?::uuid",
                Boolean.class, body.get("previewId"))).as("an omitted property stays absent in the saved request").isFalse();
        assertThat(generation()).isEqualTo(before + 1);
        JsonNode retry = apply(body);
        databaseProof("limits-retry", publicId, committed, retry);
        assertAll(
                () -> assertThat(retry.get("batchId")).isEqualTo(committed.get("batchId")),
                () -> assertThat(retry.get("items")).as("original APPLIED must not become STALE on retry").isEqualTo(committed.get("items")),
                () -> assertThat(generation()).isEqualTo(before + 1),
                () -> assertOtherLimits(key),
                () -> assertThat(targetAudits(publicId, "llm_key.limits_update")).isEqualTo(1),
                () -> assertThat(summaryAudits()).isEqualTo(1));
    }

    @Test
    void concurrentDuplicateWaitsForCommitAndReturnsTheSameCommittedResult() throws Exception {
        long key = localKey();
        UUID publicId = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(publicId),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 92)));
        long before = generation();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<JsonNode> first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
            JsonNode response = uncheckedApply(body);
            ready.countDown();
            await(commit);
            return response;
        }));
        Future<JsonNode> second = null;
        try {
            if (!ready.await(20, TimeUnit.SECONDS)) {
                if (first.isDone()) first.get(1, TimeUnit.SECONDS);
                throw new AssertionError("first apply did not reach its commit barrier");
            }
            second = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return uncheckedApply(body);
            }));
            assertThat(observeLockWait(secondPid, second)).as("actual overlapping PostgreSQL transaction lock wait").isTrue();
            commit.countDown();
            JsonNode committed = first.get(20, TimeUnit.SECONDS);
            JsonNode duplicate = second.get(20, TimeUnit.SECONDS);
            assertThat(item(committed, publicId).get("result").asString()).isEqualTo("APPLIED");
            databaseProof("overlapping-limits-duplicate", publicId, committed, duplicate);
            assertAll(
                    () -> assertThat(duplicate.get("batchId")).isEqualTo(committed.get("batchId")),
                    () -> assertThat(duplicate.get("items")).isEqualTo(committed.get("items")),
                    () -> assertThat(generation()).as("one logical change spends one generation").isEqualTo(before + 1),
                    () -> assertThat(targetAudits(publicId, "llm_key.limits_update")).isEqualTo(1),
                    () -> assertThat(summaryAudits()).isEqualTo(1));
        } finally {
            commit.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(25, TimeUnit.SECONDS)) pool.shutdownNow();
        }
    }

    @Test
    void aNewOperationIdCannotConsumeTheSamePreviewAgain() throws Exception {
        long domain = customDomain();
        UUID id = pub("domains", domain);
        Map<String, Object> body = prepared("DOMAIN", List.of(id),
                Map.of("kind", "DOMAIN_VERIFY", "domainVerify", Map.of()));
        long before = verificationJobs();
        JsonNode first = apply(body);
        assertThat(item(first, id).get("result").asString()).isEqualTo("APPLIED");
        Map<String, Object> newIdentity = new LinkedHashMap<>(body);
        newIdentity.put("operationId", UUID.randomUUID().toString());
        mvc.perform(post(APPLY).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(newIdentity)))
                .andExpect(status().isConflict());
        assertThat(verificationJobs()).isEqualTo(before + 1);
        assertThat(targetAudits(id, "domain.admin_verify")).isEqualTo(1);
        assertThat(summaryAudits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_bulk_change_operations where preview_id=?::uuid",
                Long.class, body.get("previewId"))).isEqualTo(1);
    }

    @Test
    void aCommittedOperationCannotBeReboundToAnotherChangeOrTarget() throws Exception {
        long firstKey = localKey();
        long otherKey = localKey();
        UUID id = pub("llm_api_keys", firstKey);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 93)));
        long before = generation();
        JsonNode first = apply(body);
        assertThat(item(first, id).get("result").asString()).isEqualTo("APPLIED");
        for (String changed : List.of("change", "target")) {
            Map<String, Object> rebound = new LinkedHashMap<>(body);
            if ("change".equals(changed)) rebound.put("change", Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 94)));
            else rebound.put("targetIds", List.of(pub("llm_api_keys", otherKey).toString()));
            mvc.perform(post(APPLY).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(rebound)))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, firstKey)).isEqualTo(93);
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, otherKey)).isEqualTo(60);
        assertThat(generation()).isEqualTo(before + 1);
        assertThat(targetAudits(id, "llm_key.limits_update")).isEqualTo(1);
        assertThat(targetAudits(pub("llm_api_keys", otherKey), "llm_key.limits_update")).isZero();
        assertThat(summaryAudits()).isEqualTo(1);
    }

    @Test
    void foreignAndMissingOperationReceiptsAreIndistinguishable() throws Exception {
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 95)));
        apply(body);
        User other = new User("receipt-reader-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 조회자");
        other.setRole(UserRole.SYS_ADMIN);
        other.setStatus(UserStatus.ACTIVE);
        other.setEmailVerifiedAt(Instant.now());
        other = users.save(other);
        String otherToken = jwt.createAccessToken(other);
        JsonNode foreign = readFailure(body.get("operationId").toString(), otherToken);
        JsonNode missing = readFailure(UUID.randomUUID().toString(), otherToken);
        for (String field : List.of("code", "title", "detail")) assertThat(foreign.get(field)).isEqualTo(missing.get(field));
        assertThat(foreign.toString()).doesNotContain(actor.getEmail(), "합성 결과 키", "APPLIED", "fingerprint");
        long before = generation();
        for (int i = 0; i < 3; i++) {
            JsonNode reopened = readOperation(body.get("operationId").toString());
            assertThat(reopened.get("status").asString()).isEqualTo("COMMITTED");
            assertThat(item(reopened.get("result"), id).get("result").asString()).isEqualTo("APPLIED");
        }
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isEqualTo(1);
        assertThat(targetAudits(id, "llm_key.limits_update")).isEqualTo(1);
    }

    @Test
    void aDatabaseFailureRollsBackAllTargetsAndThePreviewConsumption() throws Exception {
        long first = localKey();
        long second = localKey();
        UUID firstId = pub("llm_api_keys", first);
        UUID secondId = pub("llm_api_keys", second);
        Map<String, Object> body = prepared("LLM_KEY", List.of(firstId, secondId),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 99)));
        long before = generation();
        jdbc.execute("create function durable_fixture_failure() returns trigger language plpgsql as $$ begin raise exception 'synthetic rollback fault'; end $$");
        jdbc.execute("create trigger durable_fixture_failure before update on llm_api_keys for each row when(new.id=" + second + " and new.rpm=99) execute function durable_fixture_failure()");
        try {
            mvc.perform(post(APPLY).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                    .andExpect(status().is5xxServerError());
        } finally {
            jdbc.execute("drop trigger durable_fixture_failure on llm_api_keys");
            jdbc.execute("drop function durable_fixture_failure()");
        }
        assertThat(jdbc.queryForList("select rpm from llm_api_keys where id in (?,?) order by id", Integer.class, first, second)).containsExactly(60, 60);
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isZero();
        assertThat(targetAudits(firstId, "llm_key.limits_update")).isZero();
        assertThat(targetAudits(secondId, "llm_key.limits_update")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_bulk_change_operations where preview_id=?::uuid",
                Long.class, body.get("previewId"))).isZero();
        JsonNode retried = apply(body);
        assertThat(item(retried, firstId).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(retried, secondId).get("result").asString()).isEqualTo("APPLIED");
        assertThat(generation()).isEqualTo(before + 1);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    @Test
    void aClosedAndReopenedApplicationReturnsTheSavedReceiptWithoutEnqueuingAgain() throws Exception {
        long domain = customDomain();
        UUID id = pub("domains", domain);
        Map<String, Object> body = prepared("DOMAIN", List.of(id),
                Map.of("kind", "DOMAIN_VERIFY", "domainVerify", Map.of()));
        restartDatabase = dataSource;
        long before = verificationJobs();
        JsonNode first;
        DomainVerificationJob originalJob;
        ConfigurableApplicationContext closed;
        try (ConfigurableApplicationContext context = openApplication()) {
            closed = context;
            originalJob = context.getBean(DomainVerificationJob.class);
            first = apply(inContext(context), body);
            assertThat(item(first, id).get("result").asString()).isEqualTo("APPLIED");
            assertThat(verificationJobs()).isEqualTo(before + 1);
        }
        assertThat(closed.isActive()).as("the first application really closed").isFalse();
        try (ConfigurableApplicationContext reopened = openApplication()) {
            assertThat(reopened.getBean(DomainVerificationJob.class)).as("the new verifier has fresh in-memory dedupe state").isNotSameAs(originalJob);
            MockMvc second = inContext(reopened);
            JsonNode retry = apply(second, body);
            databaseProof("closed-and-reopened-application", id, first, retry);
            assertAll(
                    () -> assertThat(retry).isEqualTo(first),
                    () -> assertThat(verificationJobs()).isEqualTo(before + 1),
                    () -> assertThat(targetAudits(id, "domain.admin_verify")).isEqualTo(1),
                    () -> assertThat(summaryAudits()).isEqualTo(1));
            for (int i = 0; i < 2; i++) {
                JsonNode receipt = mapper.readTree(second.perform(get(APPLY + "/operations/" + body.get("operationId"))
                                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
                assertThat(receipt.get("status").asString()).isEqualTo("COMMITTED");
                assertThat(receipt.get("result")).isEqualTo(first);
            }
            assertThat(verificationJobs()).isEqualTo(before + 1);
        } finally {
            restartDatabase = null;
        }
    }

    @Test
    void anExpiredPreviewCannotStartANewOperation() throws Exception {
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 96)));
        agePreview(body.get("previewId").toString());
        long before = generation();
        mvc.perform(post(APPLY).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, key)).isEqualTo(60);
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_bulk_change_operations where preview_id=?::uuid",
                Long.class, body.get("previewId"))).isZero();
    }

    @Test
    void previewExpiryDoesNotExpireAnAlreadyCommittedReceiptOrRetry() throws Exception {
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 97)));
        JsonNode first = apply(body);
        long before = generation();
        agePreview(body.get("previewId").toString());
        assertThat(readOperation(body.get("operationId").toString()).get("result")).isEqualTo(first);
        assertThat(apply(body)).isEqualTo(first);
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isEqualTo(1);
        assertThat(targetAudits(id, "llm_key.limits_update")).isEqualTo(1);
    }

    @Test
    void anExplicitNullSurvivesSavedPreviewWhileOtherLimitsRemainPresent() throws Exception {
        long key = localKey();
        seedOtherLimits(key);
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("tpm", null);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id), Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", limits));
        assertThat(jdbc.queryForObject("select jsonb_exists(request->'change'->'llmKeyLimits','tpm') from admin_bulk_change_previews where id=?::uuid",
                Boolean.class, body.get("previewId"))).isTrue();
        assertThat(jdbc.queryForObject("select request->'change'->'llmKeyLimits'->'tpm' = 'null'::jsonb from admin_bulk_change_previews where id=?::uuid",
                Boolean.class, body.get("previewId"))).isTrue();
        long before = generation();
        JsonNode first = apply(body);
        assertThat(item(first, id).get("result").asString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select tpm from llm_api_keys where id=?", Integer.class, key)).isNull();
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, key)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select daily_tokens from llm_api_keys where id=?", Long.class, key)).isEqualTo(54321);
        assertThat(mapper.readTree(jdbc.queryForObject("select passthrough_endpoints::text from llm_api_keys where id=?", String.class, key)))
                .isEqualTo(mapper.readTree("[\"chat\",\"images\"]"));
        assertThat(readOperation(body.get("operationId").toString()).get("result")).isEqualTo(first);
        assertThat(apply(body)).isEqualTo(first);
        assertThat(generation()).isEqualTo(before + 1);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    private void seedOtherLimits(long key) {
        jdbc.update("update llm_api_keys set tpm=12345,daily_tokens=54321,concurrency=7,passthrough_endpoints='[\"chat\",\"images\"]'::jsonb where id=?", key);
    }

    private void assertOtherLimits(long key) {
        assertThat(jdbc.queryForMap("select tpm,daily_tokens,concurrency from llm_api_keys where id=?", key))
                .containsEntry("tpm", 12345).containsEntry("daily_tokens", 54321L).containsEntry("concurrency", 7);
        assertThat(mapper.readTree(jdbc.queryForObject("select passthrough_endpoints::text from llm_api_keys where id=?", String.class, key)))
                .isEqualTo(mapper.readTree("[\"chat\",\"images\"]"));
    }

    @Test
    void savedMixedResultsRetainTheirOrderAndEveryCommittedClassification() throws Exception {
        useOrgRole(org, UserRole.ORG_MANAGER);
        long foreignOrg = orgs.save(new Org("합성 범위 밖 " + UUID.randomUUID(), null)).getId();
        long applied = localKey();
        long unchanged = localKey();
        long stale = localKey();
        long denied = localKey(foreignOrg);
        jdbc.update("update llm_api_keys set rpm=101 where id=?", unchanged);
        List<UUID> ids = List.of(pub("llm_api_keys", stale), pub("llm_api_keys", denied),
                pub("llm_api_keys", unchanged), pub("llm_api_keys", applied));
        Map<String, Object> body = prepared("LLM_KEY", ids,
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 101)));
        jdbc.update("update llm_api_keys set rpm=61 where id=?", stale);
        long before = generation();
        JsonNode result = apply(body);
        List<String> actualIds = new java.util.ArrayList<>();
        List<String> classifications = new java.util.ArrayList<>();
        for (JsonNode entry : result.get("items")) {
            actualIds.add(entry.get("targetId").asString());
            classifications.add(entry.get("result").asString());
        }
        assertThat(actualIds).containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
        assertThat(classifications).containsExactly("STALE", "SKIPPED", "UNCHANGED", "APPLIED");
        assertThat(item(result, ids.get(1)).get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(item(result, ids.get(1)).get("name").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, applied)).isEqualTo(101);
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, unchanged)).isEqualTo(101);
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, stale)).isEqualTo(61);
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where id=?", Integer.class, denied)).isEqualTo(60);
        assertThat(generation()).isEqualTo(before + 1);
        assertThat(targetAudits(ids.get(3), "llm_key.limits_update")).isEqualTo(1);
        for (UUID id : ids.subList(0, 3)) assertThat(targetAudits(id, "llm_key.limits_update")).isZero();
        assertThat(readOperation(body.get("operationId").toString()).get("result")).isEqualTo(result);
        assertThat(apply(body)).isEqualTo(result);
        assertThat(generation()).isEqualTo(before + 1);
        JsonNode summary = mapper.readTree(jdbc.queryForObject("select detail::text from audit_logs where actor_id=? and action='admin.bulk_change'",
                String.class, actor.getId()));
        for (String value : classifications) assertThat(summary.get("counts").get(value).asInt()).isEqualTo(1);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    @Test
    void currentRoleAndInstitutionChangesMaskEverySavedValueWithoutRewritingHistory() throws Exception {
        useOrgRole(org, UserRole.ORG_ADMIN);
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 102)));
        JsonNode first = apply(body);
        long otherOrg = orgs.save(new Org("합성 변경된 범위 " + UUID.randomUUID(), null)).getId();
        String originalToken = token;
        useOrgRole(otherOrg, UserRole.ORG_MANAGER);
        token = originalToken;
        long before = generation();
        JsonNode preview = mapper.readTree(mvc.perform(get(APPLY + "/previews/" + body.get("previewId"))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.get("change").isNull()).isTrue();
        JsonNode previewed = item(preview, id);
        assertThat(previewed.get("name").isNull()).isTrue();
        assertThat(previewed.get("fields")).isEmpty();
        assertThat(previewed.get("fingerprint").asString()).isEmpty();
        assertThat(previewed.get("actorAccessImpact").isNull()).isTrue();
        JsonNode reopened = readOperation(body.get("operationId").toString());
        assertThat(reopened.get("change").isNull()).isTrue();
        JsonNode historical = item(reopened.get("result"), id);
        assertThat(historical.get("result").asString()).as("masking never changes the old APPLIED classification").isEqualTo("APPLIED");
        assertThat(historical.get("name").isNull()).isTrue();
        assertThat(historical.get("fields")).isEmpty();
        assertThat(reopened.get("observations").get(0).get("refusal").asString()).isEqualTo("NOT_FOUND");
        assertThat(reopened.get("observations").get(0).get("values")).isEmpty();
        for (JsonNode effect : reopened.get("effects")) {
            if (!effect.get("targetId").isNull()) assertThat(effect.get("externalReference").isNull()).isTrue();
        }
        assertThat(reopened.toString()).doesNotContain("합성 결과 키", "oldValue", "newValue", "actorAccessFingerprint");
        assertThat(apply(body)).isEqualTo(reopened.get("result"));
        assertThat(mapper.readTree(jdbc.queryForObject("select result::text from admin_bulk_change_operations where id=?::uuid",
                String.class, body.get("operationId")))).isEqualTo(first);
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    @Test
    void losingWriterStandingDoesNotExposeTheOldReceiptThroughAnyReadRoute() throws Exception {
        useOrgRole(org, UserRole.ORG_MANAGER);
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = prepared("LLM_KEY", List.of(id),
                Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 103)));
        apply(body);
        String originalToken = token;
        useOrgRole(org, UserRole.ORG_VIEWER);
        token = originalToken;
        long before = generation();
        for (String path : List.of(APPLY + "/previews/" + body.get("previewId"),
                APPLY + "/operations/" + body.get("operationId"), APPLY + "/operations")) {
            String response = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("합성 결과 키", "APPLIED", "fingerprint");
        }
        assertThat(generation()).isEqualTo(before);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    @Test
    void inclusiveKstExpiryBecomesPastExactlyAtTheNextMidnight() throws Exception {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Instant midnight = Instant.parse(date + "T15:00:00Z");
        doReturn(midnight.minusSeconds(1)).when(clock).instant();
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        Map<String, Object> body = Map.of("endDate", date.toString());
        mvc.perform(patch("/api/v1/admin/llm/keys/" + id + "/expiry").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select expires_at from llm_api_keys where id=?", java.time.OffsetDateTime.class, key).toInstant())
                .isEqualTo(midnight);
        doReturn(midnight).when(clock).instant();
        long before = generation();
        mvc.perform(patch("/api/v1/admin/llm/keys/" + id + "/expiry").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableContent());
        assertThat(generation()).isEqualTo(before);
        assertThat(targetAudits(id, "llm_key.expiry_update")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select expires_at from llm_api_keys where id=?", java.time.OffsetDateTime.class, key).toInstant())
                .isEqualTo(midnight);
    }

    @Test
    void aProvisionedProviderKeyMayBeShortenedButNotExtended() throws Exception {
        long key = localKey();
        UUID id = pub("llm_api_keys", key);
        long account = jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,?,?) returning id",
                Long.class, org, "합성 사업 " + UUID.randomUUID(), actor.getId());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Instant original = Instant.parse(today.plusDays(20) + "T15:00:00Z");
        jdbc.update("update llm_api_keys set expires_at=?,openrouter_key_hash='synthetic-provider-hash',openrouter_key_enc=?,openrouter_account_id=?,credit_limit=1 where id=?",
                java.time.OffsetDateTime.ofInstant(original, java.time.ZoneOffset.UTC), cipher.encrypt("synthetic-provider-key"), account, key);
        long before = generation();
        long jobs = jdbc.queryForObject("select count(*) from jobrunr_jobs", Long.class);
        mvc.perform(patch("/api/v1/admin/llm/keys/" + id + "/expiry").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("endDate", today.plusDays(30).toString()))))
                .andExpect(status().isConflict());
        assertThat(generation()).isEqualTo(before);
        assertThat(targetAudits(id, "llm_key.expiry_update")).isZero();
        assertThat(jdbc.queryForObject("select expires_at from llm_api_keys where id=?", java.time.OffsetDateTime.class, key).toInstant()).isEqualTo(original);
        mvc.perform(patch("/api/v1/admin/llm/keys/" + id + "/expiry").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("endDate", today.plusDays(10).toString()))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select expires_at from llm_api_keys where id=?", java.time.OffsetDateTime.class, key).toInstant())
                .isEqualTo(Instant.parse(today.plusDays(10) + "T15:00:00Z"));
        assertThat(jdbc.queryForObject("select count(*) from jobrunr_jobs", Long.class)).isEqualTo(jobs);
        assertThat(targetAudits(id, "llm_key.expiry_update")).isEqualTo(1);
    }

    @Test
    void everyDeletionBoundVmRefusesEvenAnIndefinitePeriodChange() throws Exception {
        for (String state : List.of("SCHEDULED", "REQUESTED", "DELETING", "DELETED")) {
            long vm = vm();
            UUID id = pub("vms", vm);
            if ("SCHEDULED".equals(state)) jdbc.update("update vms set delete_scheduled_for=now()+interval '1 day' where id=?", vm);
            else if ("REQUESTED".equals(state)) jdbc.update("update vms set delete_requested_at=now() where id=?", vm);
            else jdbc.update("update vms set status=?::vm_status where id=?", state, vm);
            LocalDate original = jdbc.queryForObject("select end_date from vms where id=?", LocalDate.class, vm);
            mvc.perform(patch("/api/v1/admin/vms/" + id + "/period").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"clearEndDate\":true}"))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("select end_date from vms where id=?", LocalDate.class, vm)).isEqualTo(original);
            assertThat(jdbc.queryForObject("select count(*) from vm_events where vm_id=? and type='PERIOD_UPDATE'", Long.class, vm)).isZero();
            assertThat(targetAudits(id, "vm.period_update")).isZero();
        }
    }

    @Test
    void onlyAnExternalDomainReceivesARenewalDeadline() throws Exception {
        long custom = customDomain();
        long external = jdbc.queryForObject("insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at) values(?,?,'EXTERNAL',?,'example.test','ACTIVE',now()+interval '30 days') returning id",
                Long.class, workspace, org, "durable-external-" + UUID.randomUUID() + ".example.test");
        Instant next = Instant.now().plusSeconds(60L * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Map<String, Object> body = Map.of("renewDueAt", next.toString(), "reason", "합성 기간 정정");
        mvc.perform(patch("/api/v1/admin/domains/" + pub("domains", custom) + "/renewal").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select renew_due_at from domains where id=?", java.time.OffsetDateTime.class, custom)).isNull();
        mvc.perform(patch("/api/v1/admin/domains/" + pub("domains", external) + "/renewal").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select renew_due_at from domains where id=?", java.time.OffsetDateTime.class, external).toInstant()).isEqualTo(next);
        assertThat(targetAudits(pub("domains", custom), "domain.admin_renewal")).isZero();
        assertThat(targetAudits(pub("domains", external), "domain.admin_renewal")).isEqualTo(1);
    }

    @Test
    void aDeletionScheduleRetryDoesNotPublishTheNotificationAgain() throws Exception {
        long vm = vm();
        UUID id = pub("vms", vm);
        Instant schedule = Instant.now().plusSeconds(30L * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Map<String, Object> body = prepared("VM", List.of(id), Map.of("kind", "VM_DELETION", "vmDeletion",
                Map.of("action", "SCHEDULE", "scheduledFor", schedule.toString(), "reason", "합성 예약")));
        long before = jdbc.queryForObject("select count(*) from notifications where user_id=?", Long.class, actor.getId());
        JsonNode first = apply(body);
        assertThat(item(first, id).get("result").asString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?", Long.class, actor.getId())).isEqualTo(before + 1);
        assertThat(apply(body)).isEqualTo(first);
        assertThat(readOperation(body.get("operationId").toString()).get("result")).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?", Long.class, actor.getId())).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from vm_events where vm_id=? and type='SCHEDULE_DELETE'", Long.class, vm)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status::text from vms where id=?", String.class, vm)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select delete_scheduled_for from vms where id=?", java.time.OffsetDateTime.class, vm).toInstant()).isEqualTo(schedule);
        assertThat(summaryAudits()).isEqualTo(1);
    }

    private void useOrgRole(long institution, UserRole role) {
        long actorId = actor.getId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            User managed = users.findById(actorId).orElseThrow();
            orgRoles.replaceWithSingle(managed, institution, role);
        });
        actor = users.findById(actorId).orElseThrow();
        token = jwt.createAccessToken(actor);
    }

    private void agePreview(String id) {
        // Age the consistent persisted timestamps rather than the application
        // clock, which is also used by unrelated authentication and callbacks.
        jdbc.update("""
                update admin_bulk_change_previews
                   set created_at=now()-interval '48 hours', expires_at=now()-interval '24 hours',
                       preview=jsonb_set(jsonb_set(preview,'{createdAt}',to_jsonb(now()-interval '48 hours')),
                                                    '{expiresAt}',to_jsonb(now()-interval '24 hours'))
                 where id=?::uuid
                """, id);
        assertThat(jdbc.queryForObject("select expires_at<now() from admin_bulk_change_previews where id=?::uuid",
                Boolean.class, id)).isTrue();
    }

    private ConfigurableApplicationContext openApplication() {
        // Both application lifetimes use the same test-owned embedded database.
        // No worker can execute a queued verification or deliver real mail.
        return new SpringApplicationBuilder(PickleApiApplication.class, DurableRestartDatabaseConfiguration.class)
                .run("--spring.profiles.active=test", "--server.port=0", "--server.address=127.0.0.1",
                        "--management.server.port=0", "--management.server.address=127.0.0.1",
                        "--jobrunr.background-job-server.enabled=false", "--jobrunr.dashboard.enabled=false");
    }

    private static MockMvc inContext(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private JsonNode readFailure(String operation, String readerToken) throws Exception {
        return mapper.readTree(mvc.perform(get(APPLY + "/operations/" + operation).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode readOperation(String operation) throws Exception {
        return mapper.readTree(mvc.perform(get(APPLY + "/operations/" + operation).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> prepared(String type, List<UUID> ids, Map<String, Object> change) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", type);
        body.put("targetIds", ids.stream().map(UUID::toString).toList());
        body.put("change", change);
        JsonNode preview = mapper.readTree(mvc.perform(post(PREVIEW).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (JsonNode item : preview.get("items")) fingerprints.put(item.get("targetId").asString(), item.get("fingerprint").asString());
        body.put("fingerprints", fingerprints);
        assertThat(preview.hasNonNull("previewId")).as("a real saved preview is required for candidate tests").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from admin_bulk_change_previews where id=?::uuid and actor_id=?",
                Long.class, preview.get("previewId").asString(), actor.getId())).isEqualTo(1);
        body.put("previewId", preview.get("previewId").asString());
        body.put("operationId", UUID.randomUUID().toString());
        return body;
    }

    private JsonNode apply(Map<String, Object> body) throws Exception {
        return apply(mvc, body);
    }

    private JsonNode apply(MockMvc receiver, Map<String, Object> body) throws Exception {
        JsonNode response = mapper.readTree(receiver.perform(post(APPLY).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.get("batchId").asString()).isEqualTo(body.get("operationId"));
        return response;
    }

    private JsonNode uncheckedApply(Map<String, Object> body) {
        try { return apply(body); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(25, TimeUnit.SECONDS)) throw new AssertionError("commit barrier timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private boolean observeLockWait(AtomicInteger pid, Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select coalesce((select wait_event_type='Lock' from pg_stat_activity where pid=?),false)", Boolean.class, pid.get()))) return true;
            if (future.isDone()) { future.get(); return false; }
            Thread.sleep(20);
        }
        return false;
    }

    private long customDomain() {
        long vm = vm();
        return jdbc.queryForObject("""
                insert into domains(vm_id,workspace_id,org_id,kind,fqdn,verification_token,status)
                values(?,?,?,'CUSTOM',?,'synthetic-verification','PENDING') returning id
                """, Long.class, vm, workspace, org, "durable-" + UUID.randomUUID() + ".example.test");
    }

    private long vm() {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select id from nodes where name='pve1'", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace, org, actor.getId(), "합성 검증", image);
        RequestFixtures.approveVmRequest(jdbc, request, actor.getId(), image, 2, 2048, 10);
        String name = "durable-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status,end_date)
                values(?,?,?,?,?,?,?,2,2048,10,'RUNNING',?) returning id
                """, Long.class, node, workspace, org, request, name, name, image, LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30));
    }

    private long localKey() {
        return localKey(org);
    }

    private long localKey(long institution) {
        long request = RequestFixtures.insertLlmKeyRequest(jdbc, workspace, institution, actor.getId(), "합성 결과 검증");
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        return jdbc.queryForObject("""
                insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,token_hash,token_prefix,status,
                    rpm,tpm,concurrency,daily_tokens,credit_limit,created_by)
                values(?,?,?,'합성 결과 키','합성 검증',?,?,'ACTIVE',60,1000,4,10000,0,?) returning id
                """, Long.class, workspace, institution, request, hash, "pickle-" + hash.substring(0, 6), actor.getId());
    }

    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private long generation() { return jdbc.queryForObject("select coalesce((select generation from llm_gateway_state where id=true),0)", Long.class); }
    private long verificationJobs() { return jdbc.queryForObject("select count(*) from jobrunr_jobs where jobsignature like '%DomainVerificationJob.verify(%'", Long.class); }
    private long targetAudits(UUID id, String action) { return jdbc.queryForObject("select count(*) from audit_logs where action=? and target_id=?", Long.class, action, id.toString()); }
    private long summaryAudits() { return jdbc.queryForObject("select count(*) from audit_logs where actor_id=? and action='admin.bulk_change'", Long.class, actor.getId()); }
    private void databaseProof(String stage, UUID target, JsonNode first, JsonNode retry) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("stage", stage);
        proof.put("postgres", jdbc.queryForObject("select version()", String.class));
        proof.put("database", jdbc.queryForObject("select current_database()", String.class));
        proof.put("targetId", target.toString());
        proof.put("first", first);
        proof.put("duplicate", retry);
        proof.put("gatewayGeneration", generation());
        proof.put("verificationJobs", verificationJobs());
        proof.put("targetAuditCount", jdbc.queryForObject("select count(*) from audit_logs where target_id=?", Long.class, target.toString()));
        proof.put("summaryAuditCount", summaryAudits());
        System.out.println("DURABILITY_DB " + mapper.writeValueAsString(proof));
    }
    private static JsonNode item(JsonNode response, UUID id) {
        for (JsonNode item : response.get("items")) if (id.toString().equals(item.get("targetId").asString())) return item;
        throw new AssertionError("missing target " + id);
    }
}

