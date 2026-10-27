package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.openrouter.LlmOpenRouterProvisioner;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterClient;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterManagementCredentialCipher;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP reads and writes distinguish account populations and commitment facts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, AdminLlmAccountExplorationTest.ClockBeans.class})
class AdminLlmAccountExplorationTest {
    private static final Instant FROM = Instant.parse("2021-02-03T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(3600);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;
    @Autowired private WorkspaceRepository workspaces;
    @MockitoSpyBean private OpenRouterAllocationQuery allocations;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableClock testClock;
    @Autowired private OpenRouterManagementCredentialCipher managementCipher;
    @MockitoBean private LlmOpenRouterProvisioner provisioner;
    @MockitoBean private OpenRouterClient vendor;
    @Autowired private OpenRouterCatalogueRepository catalogue;

    private Org org;
    private Workspace workspace;
    private User owner;
    private User reader;

    @BeforeEach
    void isolatedAccountRows() {
        testClock.set(Instant.now());
        String suffix = UUID.randomUUID().toString();
        org = orgs.save(new Org("Exploration institution " + suffix, null));
        workspace = workspaces.save(new Workspace(WorkspaceKind.PROJECT,
                "Exploration workspace " + suffix, null));
        owner = user(UserRole.USER);
        reader = user(UserRole.SYS_ADMIN);
        jdbc.update("delete from llm_usage_rollup_state");
        clearInvocations(provisioner, vendor);
    }

    @Test
    void awaitingProvisionCohortUsesTheSameFilteredMembershipAndCount() throws Exception {
        Account account = account("cohort");
        UUID pending = key(account, "awaiting issuance", "PENDING", "10", null, null);
        key(account, "observed zero", "ACTIVE", "10", "synthetic-observed", "0");
        key(account, "issued but unreported", "ACTIVE", "10", "synthetic-unreported", null);
        String url = "/api/v1/admin/llm/keys?orgId=" + org.getPublicId()
                + "&openrouterAccountId=" + account.publicId() + "&size=20";

        JsonNode all = read(url);
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        JsonNode awaiting = read(url + "&cohort=AWAITING_PROVISION");

        assertThat(awaiting.get("totalElements").asLong()).isEqualTo(1);
        assertThat(awaiting.get("content").size()).isEqualTo(1);
        assertThat(awaiting.get("content").get(0).get("id").asText()).isEqualTo(pending.toString());
    }

    @Test
    void aSecondPressurePageDoesNotRepeatTheFirstTruncatedItem() throws Exception {
        Account account = account("pressure pages");
        key(account, "first pressure key", "ACTIVE", "0", null, null);
        key(account, "second pressure key", "ACTIVE", "0", null, null);
        String url = "/api/v1/admin/llm/usage?orgId=" + org.getPublicId()
                + "&days=7&top=1&reviewSize=1";
        JsonNode first = read(url + "&reviewPage=0").get("limitReview");
        JsonNode second = read(url + "&reviewPage=1").get("limitReview");

        assertThat(first.get("totalItems").asLong()).isEqualTo(2);
        assertThat(first.get("truncated").asBoolean()).isTrue();
        assertThat(first.get("items").size()).isEqualTo(1);
        assertThat(second.get("items").size()).isEqualTo(1);
        assertThat(second.get("items").get(0).get("keyId").asText())
                .isNotEqualTo(first.get("items").get(0).get("keyId").asText());
    }

    @Test
    void accountConsumptionFilterPartitionsOneExactWindowBeforeEveryAggregate() throws Exception {
        Account selected = account("selected consumption");
        Account other = account("other consumption");
        UUID selectedKey = key(selected, "selected window key", "ACTIVE", "0", null, null);
        UUID otherKey = key(other, "other window key", "ACTIVE", "0", null, null);
        event(selectedKey, FROM.plusSeconds(60), 11);
        event(otherKey, FROM.plusSeconds(120), 29);
        event(selectedKey, TO, 999);
        String url = "/api/v1/admin/llm/capacity?orgId=" + org.getPublicId()
                + "&from=" + FROM + "&to=" + TO + "&group=KEY&size=20";

        assertThat(read(url).get("summary").get("requests").asLong()).isEqualTo(2);
        JsonNode selectedWindow = read(url + "&openrouterAccountId=" + selected.publicId());

        assertThat(selectedWindow.get("summary").get("requests").asLong()).isEqualTo(1);
        assertThat(selectedWindow.get("summary").get("inputTokens").asLong()).isEqualTo(11);
        assertThat(selectedWindow.get("consumers").get("totalElements").asLong()).isEqualTo(1);
        assertThat(selectedWindow.get("consumers").get("items").get(0).get("keyId").asText())
                .isEqualTo(selectedKey.toString());
    }

    @Test
    void aReducedCapAuditMatchesTheClampedStandingCommitmentAfterTheWrite() throws Exception {
        Account account = account("clamped commitment");
        UUID changing = key(account, "nearly spent", "ACTIVE", "10", "synthetic-changing", "9");
        key(account, "other remaining promise", "ACTIVE", "3", "synthetic-other", "0");
        jdbc.update("""
                update openrouter_accounts set credits_total=100,credits_usage=98,
                    credits_observed_at=now(),credits_last_success_at=now() where id=?
                """, account.id());
        assertThat(allocations.forAccount(account.id()).remainingCommitment()).isEqualByComparingTo("4");

        mvc.perform(put("/api/v1/admin/llm/keys/" + changing + "/limits")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"creditLimit\":5}"))
                .andExpect(status().isOk());

        BigDecimal standing = allocations.forAccount(account.id()).remainingCommitment();
        assertThat(standing).isEqualByComparingTo("3");
        String detail = jdbc.queryForObject("""
                select detail::text from audit_logs where target_id=? and action='llm_key.limits_update'
                order by id desc limit 1
                """, String.class, changing.toString());
        JsonNode audit = json.readTree(detail);
        assertThat(audit.get("accountProjectedRemainingCommitment").decimalValue())
                .isEqualByComparingTo(standing);
        assertThat(audit.get("overAllocated").asBoolean()).isTrue();
    }

    @Test
    void reopeningAClosedVendorKeyInAnotherWindowDoesNotReuseItsOldMeter() throws Exception {
        Account account = account("reopened window");
        UUID key = key(account, "closed then reopened", "ACTIVE", "10", "synthetic-reopened", "9");
        jdbc.update("update llm_api_keys set credit_limit_reset='MONTHLY' where public_id=?", key);
        jdbc.update("""
                update openrouter_accounts set credits_total=100,credits_usage=90,
                    credits_observed_at=now(),credits_last_success_at=now() where id=?
                """, account.id());
        mvc.perform(put("/api/v1/admin/llm/keys/" + key + "/limits")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":0,\"creditLimitReset\":null}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select openrouter_usage from llm_api_keys where public_id=?",
                BigDecimal.class, key)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("select openrouter_key_hash is not null and openrouter_account_id=? "
                + "from llm_api_keys where public_id=?", Boolean.class, account.id(), key)).isTrue();

        mvc.perform(put("/api/v1/admin/llm/keys/" + key + "/limits")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":5,\"creditLimitReset\":\"DAILY\"}"))
                .andExpect(status().isOk());

        String detail = jdbc.queryForObject("""
                select detail::text from audit_logs where target_id=? and action='llm_key.limits_update'
                order by id desc limit 1
                """, String.class, key.toString());
        JsonNode audit = json.readTree(detail);
        assertThat(audit.get("accountProjectedRemainingCommitment").isNull()).isTrue();
        assertThat(audit.get("overAllocated").isNull()).isTrue();
    }

    @Test
    void aCommittedMeterUpdateCannotMixReadBasesOrBeOverwrittenByTheLimitWrite() throws Exception {
        Account account = account("concurrent meter basis");
        UUID key = key(account, "meter advances after refresh", "ACTIVE", "10", "synthetic-concurrent", "9");
        try (var worker = Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                LlmApiKey loaded = invocation.getArgument(1);
                assertThat(loaded.getOpenrouterUsage()).isEqualByComparingTo("9");
                worker.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.update("""
                            update llm_api_keys set openrouter_usage=10,openrouter_usage_at=now(),
                                openrouter_limit_remaining=0 where public_id=?
                            """, key);
                })).get(15, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(allocations).replacementGrantRecord(anyLong(), any(LlmApiKey.class),
                    any(BigDecimal.class), nullable(CreditLimitReset.class));

            mvc.perform(put("/api/v1/admin/llm/keys/" + key + "/limits")
                            .header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"creditLimit\":5}"))
                    .andExpect(status().isOk());
        }
        String detail = jdbc.queryForObject("""
                select detail::text from audit_logs where target_id=? and action='llm_key.limits_update'
                order by id desc limit 1
                """, String.class, key.toString());
        JsonNode audit = json.readTree(detail);
        BigDecimal meter = jdbc.queryForObject("select openrouter_usage from llm_api_keys where public_id=?",
                BigDecimal.class, key);
        BigDecimal remaining = jdbc.queryForObject(
                "select openrouter_limit_remaining from llm_api_keys where public_id=?", BigDecimal.class, key);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(audit.get("accountProjectedRemainingCommitment").decimalValue())
                    .as("projection uses the committed meter from the same statement as the account sum")
                    .isEqualByComparingTo("0");
            softly.assertThat(meter).as("a limits write does not own the vendor meter").isEqualByComparingTo("10");
            softly.assertThat(remaining).as("a limits write does not own reported remaining").isEqualByComparingTo("0");
        });
    }

    @Test
    void subMicrosecondClockUsesOneStrictExpiryBasisForSumAndContribution() {
        Account account = account("strict expiry clock");
        UUID key = key(account, "expires at microsecond grid", "ACTIVE", "10", "synthetic-expiry", "0");
        long internal = jdbc.queryForObject("select id from llm_api_keys where public_id=?", Long.class, key);
        Instant expiry = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update llm_api_keys set expires_at=? where public_id=?", Timestamp.from(expiry), key);
        testClock.set(expiry.minusNanos(400));

        var before = allocations.forAccount(account.id());
        var basis = allocations.snapshot(List.of(account.id()), List.of(internal));
        var proposed = OpenRouterAllocationQuery.project(basis.allocations().get(account.id()), List.of(
                basis.keys().get(internal).replacement(account.id(), new BigDecimal("5"), null)));
        testClock.set(expiry.plusNanos(400));
        var expired = allocations.forAccount(account.id());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(before.committedCreditLimit()).as("expiry is strictly after the actual clock")
                    .isEqualByComparingTo("10");
            softly.assertThat(proposed.remainingCommitment()).as("the same future key contributes once to both sides")
                    .isEqualByComparingTo("5");
            softly.assertThat(expired.committedCreditLimit()).as("expiry is strictly before the actual clock")
                    .isEqualByComparingTo("0");
        });
    }

    @Test
    void singleAndBulkExposeTheSameReadableCachedCommitmentProjection() throws Exception {
        Account account = account("same server projection");
        UUID key = key(account, "single and bulk proposal", "ACTIVE", "10", "synthetic-shared", "5");
        jdbc.update("""
                update openrouter_accounts set credits_total=100,credits_usage=83,credits_observed_at=now(),
                    credits_last_success_at=now(),keys_last_success_at=now() where id=?
                """, account.id());
        JsonNode single = read("/api/v1/admin/llm/keys/" + key
                + "?projectLimits=true&proposedCreditLimit=20");
        JsonNode projection = single.get("limitsProjection");
        assertThat(projection).isNotNull();
        assertThat(projection.get("draft").get("creditLimitSet").asBoolean()).isTrue();
        assertThat(projection.get("draft").get("creditLimit").decimalValue()).isEqualByComparingTo("20");
        assertThat(projection.get("draft").get("creditLimitResetSet").asBoolean()).isFalse();
        assertThat(projection.get("draft").get("creditLimitReset").isNull()).isTrue();
        assertThat(projection.get("draft").get("openrouterAccountId").isNull()).isTrue();
        assertProjection(projection.get("accountImpact"), "5", "15", false);
        assertThat(single.get("creditLimit").decimalValue()).isEqualByComparingTo("10");

        String body = json.writeValueAsString(Map.of("targetType", "LLM_KEY", "targetIds", List.of(key),
                "change", Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("creditLimit", 20))));
        JsonNode bulk = json.readTree(mvc.perform(post("/api/v1/admin/bulk-changes/preview")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertProjection(bulk.get("accountImpact"), "5", "15", false);
        assertThat(bulk.get("accountImpact").get("basisFingerprint").asText())
                .isEqualTo(projection.get("accountImpact").get("basisFingerprint").asText());
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?",
                BigDecimal.class, key)).isEqualByComparingTo("10");
        verifyNoInteractions(provisioner, vendor);
        writeExample("single-projection-response", single);
        writeExample("bulk-preview-response", bulk);
    }

    @Test
    void accountUsagePagesKeepTheWholeRollupPopulationAndTotals() throws Exception {
        Account account = account("paged rollup attribution");
        UUID first = key(account, "higher requests", "ACTIVE", "10", null, null);
        UUID second = key(account, "lower requests", "ACTIVE", "10", null, null);
        key(account, "linked but not observed in the rollup", "SUSPENDED", "10", null, null);
        daily(first, 7, 70, "1.25", 7);
        daily(second, 3, 30, null, 0);
        String url = "/api/v1/admin/llm/accounts/" + account.publicId()
                + "/usage?days=7&size=1&sort=REQUESTS_DESC";
        JsonNode one = read(url + "&page=0");
        JsonNode two = read(url + "&page=1");
        JsonNode outside = read(url + "&page=99");
        assertThat(one.get("keysUsed").asInt()).isEqualTo(2);
        assertThat(one.get("keysLinked").asInt()).isEqualTo(3);
        assertThat(one.get("totalKeyElements").asLong()).isEqualTo(2);
        assertThat(one.get("totalKeyPages").asInt()).isEqualTo(2);
        assertThat(one.get("requests").asLong()).isEqualTo(10);
        assertThat(one.get("attributedCostUsd").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(one.get("keys").get(0).get("keyId").asText()).isEqualTo(first.toString());
        assertThat(two.get("keys").get(0).get("keyId").asText()).isEqualTo(second.toString());
        assertThat(two.get("requests").asLong()).isEqualTo(10);
        assertThat(two.get("keys").get(0).get("attributedCostUsd").isNull()).isTrue();
        assertThat(outside.get("keys").size()).isZero();
        assertThat(outside.get("totalKeyElements").asLong()).isEqualTo(2);
        assertThat(outside.get("requests").asLong()).isEqualTo(10);
        assertThat(one.get("timezone").asText()).isEqualTo("Asia/Seoul");
        assertThat(one.get("source").asText()).isEqualTo("DAILY_ROLLUP_ATTRIBUTION_CURRENT_ACCOUNT_BINDING");
        assertThat(one.get("from").asText()).isEqualTo(testClock.instant().atZone(ZoneOffset.ofHours(9)).toLocalDate().minusDays(6).toString());
        writeExample("account-usage-paged-response", one);
    }

    @Test
    void legacyConsumersPageTheirOwnRollupRelationAndEchoItsWindow() throws Exception {
        Account account = account("legacy consumers");
        Workspace original = workspace;
        UUID low = key(account, "lower workspace requests", "ACTIVE", "0", null, null);
        workspace = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Second consumer workspace " + UUID.randomUUID(), null));
        Workspace highWorkspace = workspace;
        UUID high = key(account, "higher workspace requests", "ACTIVE", "0", null, null);
        workspace = original;
        daily(low, 3, 30, null, 0);
        daily(high, 9, 90, "2.50", 9);
        String url = "/api/v1/admin/llm/usage?orgId=" + org.getPublicId()
                + "&days=7&top=1&consumerSize=1&consumerSort=REQUESTS_DESC";
        JsonNode one = read(url + "&consumerPage=0").get("consumers");
        JsonNode two = read(url + "&consumerPage=1").get("consumers");
        JsonNode outside = read(url + "&consumerPage=8").get("consumers");
        assertThat(one.get("totalItems").asLong()).isEqualTo(2);
        assertThat(one.get("items").get(0).get("workspaceId").asText()).isEqualTo(highWorkspace.getPublicId().toString());
        assertThat(two.get("items").get(0).get("workspaceId").asText()).isEqualTo(original.getPublicId().toString());
        assertThat(outside.get("items").size()).isZero();
        assertThat(outside.get("totalItems").asLong()).isEqualTo(2);
        assertThat(one.get("source").asText()).isEqualTo("DAILY_ROLLUP_AND_VENDOR_METER_SNAPSHOTS");
        assertThat(one.get("sort").asText()).isEqualTo("REQUESTS_DESC");
        assertThat(one.get("from").asText()).isEqualTo(testClock.instant().atZone(ZoneOffset.ofHours(9)).toLocalDate().minusDays(6).toString());
    }

    @Test
    void vendorRemainingSortKeepsUnreportedRowsAndUsesStableNullPlacement() throws Exception {
        Account account = account("remaining order");
        UUID unknown = key(account, "unreported remaining", "ACTIVE", "10", "synthetic-null-remaining", null);
        UUID zero = key(account, "observed zero remaining", "ACTIVE", "10", "synthetic-zero-remaining", "10");
        UUID five = key(account, "observed five remaining", "ACTIVE", "10", "synthetic-five-remaining", "5");
        jdbc.update("update llm_api_keys set openrouter_limit_remaining=0 where public_id=?", zero);
        jdbc.update("update llm_api_keys set openrouter_limit_remaining=5 where public_id=?", five);
        String url = "/api/v1/admin/llm/keys?openrouterAccountId=" + account.publicId()
                + "&sort=CREDIT_REMAINING_ASC&size=2";
        JsonNode one = read(url + "&page=0");
        JsonNode two = read(url + "&page=1");
        assertThat(one.get("totalElements").asLong()).isEqualTo(3);
        assertThat(one.get("content").get(0).get("id").asText()).isEqualTo(zero.toString());
        assertThat(one.get("content").get(1).get("id").asText()).isEqualTo(five.toString());
        assertThat(two.get("content").get(0).get("id").asText()).isEqualTo(unknown.toString());
        assertThat(two.get("content").get(0).get("creditLimitRemaining").isNull()).isTrue();
        assertThat(read(url + "&page=0").get("content")).isEqualTo(one.get("content"));
    }

    @Test
    void proposalReadsDoNotBindOrWriteAndMissingAccountFinanceDoesNotBlockTheSingleCommand() throws Exception {
        Account account = account("pure first binding proposal");
        activate(account);
        UUID key = key(null, "unbound proposal", "ACTIVE", "0", null, null);
        User actor = user(UserRole.ORG_MANAGER);
        grant(actor, Set.of(InstitutionWork.LLM_KEY_BUDGET));
        String token = jwt.createAccessToken(actor);
        String url = "/api/v1/admin/llm/keys/" + key + "?projectLimits=true&proposedCreditLimit=5"
                + "&proposedOpenrouterAccountId=" + account.publicId();
        Map<String, Object> before = businessState(key, account);
        JsonNode first = readToken(token, url);
        JsonNode repeated = readToken(token, url);
        assertThat(first.get("limitsProjection").get("accountImpact").get("state").asText()).isEqualTo("NOT_PROVIDED");
        assertThat(repeated.get("limitsProjection").get("accountImpact").get("accounts").isNull()).isTrue();
        assertThat(first.get("limitsProjection").get("draft").get("openrouterAccountId").asText())
                .isEqualTo(account.publicId().toString());
        assertThat(businessState(key, account)).isEqualTo(before);
        verifyNoInteractions(provisioner, vendor);
        writeExample("single-not-provided-response", first);

        mvc.perform(put("/api/v1/admin/llm/keys/" + key + "/limits")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("creditLimit", 5, "openrouterAccountId", account.publicId()))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select openrouter_account_id from llm_api_keys where public_id=?",
                Long.class, key)).isEqualTo(account.id());
    }

    @Test
    void resetMismatchUnchangedUnreportedKeysAndStaleRemainIndependentQualityFacts() throws Exception {
        Account account = account("independent quality");
        UUID changing = key(account, "changes window", "ACTIVE", "10", "synthetic-changing-window", "9");
        key(account, "unaffected unreported key", "ACTIVE", "7", "synthetic-unreported-quality", null);
        jdbc.update("update llm_api_keys set credit_limit_reset='MONTHLY' where public_id=?", changing);
        jdbc.update("""
                update openrouter_accounts set credits_total=100,credits_usage=20,credits_observed_at=now(),
                    credits_last_success_at=now()-interval '2 hours',keys_last_success_at=now()-interval '2 hours'
                where id=?
                """, account.id());
        JsonNode preview = bulkPreview(reader, List.of(changing), Map.of("creditLimit", 5, "creditLimitReset", "DAILY"));
        JsonNode impact = preview.get("accountImpact").get("accounts").get(0);
        JsonNode quality = impact.get("quality");
        assertThat(impact.get("after").get("remainingCommitment").isNull()).isTrue();
        assertThat(impact.get("overAllocated").isNull()).isTrue();
        assertThat(quality.get("periodBasisMismatch").asBoolean()).isTrue();
        assertThat(quality.get("changedPeriodKeys").asLong()).isEqualTo(1);
        assertThat(quality.get("beforeUsageUnreportedKeys").asLong()).isEqualTo(1);
        assertThat(quality.get("afterUsageUnreportedKeys").asLong()).isEqualTo(1);
        assertThat(quality.get("creditsFreshness").asText()).isEqualTo("STALE");
        assertThat(quality.get("keyMetersFreshness").asText()).isEqualTo("STALE");
        assertThat(quality.get("source").asText()).isEqualTo("SERVER_CACHED_ALLOCATION");
        assertThat(quality.get("vendorWindowAlignmentUnconfirmed").asBoolean()).isTrue();
    }

    @Test
    void savedImpactRemasksAccountFinanceWhileFreshPermittedCommandsRemainUsable() throws Exception {
        Account account = account("durable finance remasking");
        UUID key = key(account, "still writable without account finance", "ACTIVE", "10", "synthetic-remask", "5");
        User actor = user(UserRole.ORG_MANAGER);
        Set<InstitutionWork> readsAndCommand = Set.of(InstitutionWork.LLM_KEY_BUDGET,
                InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ);
        Set<InstitutionWork> withFinance = new java.util.HashSet<>(readsAndCommand);
        withFinance.add(InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ);
        grant(actor, withFinance);
        String token = jwt.createAccessToken(actor);
        JsonNode old = bulkPreview(actor, List.of(key), Map.of("creditLimit", 20));
        assertThat(old.get("accountImpact").get("state").asText()).isEqualTo("AVAILABLE");
        grant(actor, readsAndCommand);
        JsonNode masked = readToken(token, "/api/v1/admin/bulk-changes/previews/" + old.get("previewId").asText());
        assertThat(masked.get("accountImpact").get("state").asText()).isEqualTo("NOT_PROVIDED");
        assertThat(masked.get("accountImpact").get("accounts").isNull()).isTrue();
        assertThat(masked.get("accountImpact").get("basisFingerprint").isNull()).isTrue();
        JsonNode fresh = bulkPreview(actor, List.of(key), Map.of("creditLimit", 20));
        assertThat(fresh.get("items").get(0).get("applicable").asBoolean()).isTrue();
        assertThat(fresh.get("accountImpact").get("state").asText()).isEqualTo("NOT_PROVIDED");
        Map<String, Object> apply = bulkRequest(List.of(key), Map.of("creditLimit", 20));
        apply.put("previewId", fresh.get("previewId").asText());
        apply.put("operationId", UUID.randomUUID().toString());
        Map<String, String> fingerprints = new LinkedHashMap<>();
        fresh.get("items").forEach(item -> fingerprints.put(item.get("targetId").asText(), item.get("fingerprint").asText()));
        apply.put("fingerprints", fingerprints);
        JsonNode committed = sendToken(token, post("/api/v1/admin/bulk-changes").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(apply)));
        assertThat(committed.get("items").get(0).get("result").asText()).isEqualTo("APPLIED");
        writeExample("bulk-not-provided-response", fresh);
    }

    @Test
    void unreadableObservationAndMoneyCannotSelectOrOrderVisibleKeys() throws Exception {
        Account account = account("closed exploration fields");
        UUID key = key(account, "metadata only row", "ACTIVE", "10", "synthetic-hidden", "9");
        User actor = user(UserRole.ORG_VIEWER);
        grant(actor, Set.of(InstitutionWork.LLM_METADATA_READ));
        String token = jwt.createAccessToken(actor);
        String base = "/api/v1/admin/llm/keys?orgId=" + org.getPublicId();
        for (String query : List.of("&sort=TOKEN_USAGE_DESC", "&sort=CREDIT_LIMIT_DESC", "&cohort=USAGE_UNREPORTED")) {
            mvc.perform(get(base + query).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        jdbc.update("update llm_api_keys set credit_limit=100,openrouter_usage=1 where public_id=?", key);
        for (String query : List.of("&sort=TOKEN_USAGE_DESC", "&sort=CREDIT_LIMIT_DESC", "&cohort=USAGE_UNREPORTED")) {
            mvc.perform(get(base + query).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        JsonNode ordinary = readToken(token, base);
        assertThat(ordinary.get("totalElements").asLong()).isEqualTo(1);
        assertThat(ordinary.get("content").get(0).get("tokenObservation").isNull()).isTrue();
        assertThat(ordinary.get("content").get(0).get("creditUsage").isNull()).isTrue();
    }

    @Test
    void foreignIndividualFinanceCannotProbeUnboundAccountSelection() throws Exception {
        UUID key = key(null, "foreign finance reference", "ACTIVE", "0", null, null);
        User actor = user(UserRole.ORG_ADMIN);
        Org granted = orgs.save(new Org("Other full institution " + UUID.randomUUID(), null));
        var assignment = InstitutionAssignment.full(actor.getPublicId(), granted.getPublicId(), 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("""
                insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)
                values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))
                """, actor.getId(), granted.getId(), assignment.displayRole().name(), encoded.mode(), encoded.payload());
        String token = jwt.createAccessToken(actor);
        JsonNode base = readToken(token, "/api/v1/admin/llm/keys/" + key);
        assertThat(base.get("financeVisible").asBoolean()).isTrue();
        assertThat(base.get("budgetReferenceVisible").asBoolean()).isFalse();
        String url = "/api/v1/admin/llm/keys/" + key + "?projectLimits=true&proposedCreditLimit=1";
        List<Integer> defaultStatuses = new java.util.ArrayList<>();
        defaultStatuses.add(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus());
        Account first = account("first invisible selection");
        activate(first);
        assertThat(read("/api/v1/admin/llm/accounts/" + first.publicId()).get("eligibleForBinding").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/admin/llm/accounts/" + first.publicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        defaultStatuses.add(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus());
        Account second = account("second invisible selection");
        activate(second);
        defaultStatuses.add(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus());
        List<Integer> explicitStatuses = List.of(
                mvc.perform(get(url + "&proposedOpenrouterAccountId=" + first.publicId())
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus(),
                mvc.perform(get(url + "&proposedOpenrouterAccountId=" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(defaultStatuses).as("undiscoverable zero, one and multiple accounts")
                    .containsExactly(200, 200, 200);
            softly.assertThat(explicitStatuses).as("undiscoverable eligible and unknown UUID")
                    .containsExactly(200, 200);
        });
    }

    @Test
    void multipleAccountsProjectAllEligibleChangesWithoutAddingAFinancialApplyGate() throws Exception {
        Account first = account("whole change first");
        Account second = account("whole change second");
        Account skippedAccount = account("skipped account");
        UUID a = key(first, "nearly spent first", "ACTIVE", "10", "synthetic-multi-a", "9");
        UUID b = key(first, "other first promise", "ACTIVE", "3", "synthetic-multi-b", "0");
        UUID c = key(second, "second changed", "ACTIVE", "2", "synthetic-multi-c", "1");
        UUID unchanged = key(second, "second unchanged", "ACTIVE", "5", "synthetic-multi-unchanged", "0");
        key(second, "unaffected unreported", "ACTIVE", "7", "synthetic-multi-unreported", null);
        UUID skipped = key(skippedAccount, "revoked target", "REVOKED", "100", "synthetic-multi-skipped", "0");
        jdbc.update("update openrouter_accounts set credits_total=100,credits_usage=96,credits_observed_at=now(),"
                + "credits_last_success_at=now() where id=?", first.id());
        jdbc.update("update openrouter_accounts set credits_total=100,credits_usage=85,credits_observed_at=now(),"
                + "credits_last_success_at=now() where id=?", second.id());
        List<UUID> targets = List.of(a, b, c, unchanged, skipped);
        JsonNode preview = bulkPreview(reader, targets, Map.of("creditLimit", 5));
        assertThat(preview.get("accountImpact").get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(preview.get("accountImpact").get("accounts").size()).isEqualTo(2);
        JsonNode firstImpact = impactAccount(preview, first.publicId());
        JsonNode secondImpact = impactAccount(preview, second.publicId());
        assertThat(firstImpact.get("changedKeys").asInt()).isEqualTo(2);
        assertThat(firstImpact.get("before").get("remainingCommitment").decimalValue()).isEqualByComparingTo("4");
        assertThat(firstImpact.get("after").get("remainingCommitment").decimalValue()).isEqualByComparingTo("5");
        assertThat(secondImpact.get("changedKeys").asInt()).isEqualTo(1);
        assertThat(secondImpact.get("before").get("remainingCommitment").decimalValue()).isEqualByComparingTo("13");
        assertThat(secondImpact.get("after").get("remainingCommitment").decimalValue()).isEqualByComparingTo("16");
        assertThat(secondImpact.get("quality").get("afterUsageUnreportedKeys").asLong()).isEqualTo(1);
        assertThat(firstImpact.get("overAllocated").asBoolean()).isTrue();
        assertThat(secondImpact.get("overAllocated").asBoolean()).isTrue();
        assertThat(preview.get("items").size()).isEqualTo(5);
        writeExample("multi-account-preview-response", preview);

        // These are information observations, not a new financial compare-and-set.
        jdbc.update("update openrouter_accounts set credits_usage=99 where id=?", first.id());
        jdbc.update("update llm_api_keys set openrouter_usage=2 where public_id=?", c);
        Map<String, Object> request = bulkRequest(targets, Map.of("creditLimit", 5));
        request.put("previewId", preview.get("previewId").asText());
        request.put("operationId", UUID.randomUUID().toString());
        Map<String, String> hashes = new LinkedHashMap<>();
        preview.get("items").forEach(item -> hashes.put(item.get("targetId").asText(), item.get("fingerprint").asText()));
        request.put("fingerprints", hashes);
        JsonNode applied = sendToken(jwt.createAccessToken(reader), post("/api/v1/admin/bulk-changes")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
        List<String> results = new java.util.ArrayList<>();
        applied.get("items").forEach(item -> results.add(item.get("result").asText()));
        assertThat(results).containsExactly("APPLIED", "APPLIED", "APPLIED", "UNCHANGED", "SKIPPED");
        JsonNode saved = read("/api/v1/admin/bulk-changes/previews/" + preview.get("previewId").asText());
        assertThat(saved.get("accountImpact")).isEqualTo(preview.get("accountImpact"));
        assertThat(allocations.forAccount(second.id()).remainingCommitment()).isEqualByComparingTo("15");
        assertThat(sendToken(jwt.createAccessToken(reader), post("/api/v1/admin/bulk-changes")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))).isEqualTo(applied);
    }

    @Test
    void proposalPresenceAndExplicitTotalResetEchoTheExactDraft() throws Exception {
        Account account = account("proposal presence");
        UUID key = key(account, "windowed proposal", "ACTIVE", "10", "synthetic-presence", "5");
        jdbc.update("update llm_api_keys set credit_limit_reset='MONTHLY' where public_id=?", key);
        String base = "/api/v1/admin/llm/keys/" + key;
        JsonNode unchanged = read(base + "?projectLimits=true");
        JsonNode unchangedDraft = unchanged.get("limitsProjection").get("draft");
        assertThat(unchangedDraft.get("creditLimitSet").asBoolean()).isFalse();
        assertThat(unchangedDraft.get("creditLimitResetSet").asBoolean()).isFalse();
        assertThat(unchangedDraft.get("creditLimitReset").isNull()).isTrue();
        assertThat(unchangedDraft.get("openrouterAccountId").isNull()).isTrue();
        JsonNode total = read(base + "?projectLimits=true&proposedCreditLimitResetSet=true");
        assertThat(total.get("limitsProjection").get("draft").get("creditLimitResetSet").asBoolean()).isTrue();
        assertThat(total.get("limitsProjection").get("draft").get("creditLimitReset").isNull()).isTrue();
        assertThat(total.get("limitsProjection").get("accountImpact").get("accounts").get(0)
                .get("after").get("remainingCommitment").isNull()).isTrue();
        String token = jwt.createAccessToken(reader);
        for (String query : List.of("?proposedCreditLimit=1", "?projectLimits=true&proposedCreditLimit=",
                "?projectLimits=true&proposedCreditLimit=-1", "?projectLimits=true&proposedCreditLimit=0.001",
                "?projectLimits=true&proposedCreditLimit=10000000000", "?projectLimits=true&proposedCreditLimitReset=DAILY",
                "?projectLimits=true&proposedCreditLimit=0&proposedCreditLimitResetSet=true&proposedCreditLimitReset=DAILY",
                "?projectLimits=true&proposedOpenrouterAccountId=")) {
            mvc.perform(get(base + query).header("Authorization", "Bearer " + token)).andExpect(status().isUnprocessableContent());
        }
        verifyNoInteractions(provisioner, vendor);
    }

    @Test
    void accountNavigationCountsItsReadableSubsetWhileAllocationKeepsTheWholeAccount() throws Exception {
        Account account = account("readable navigation subset");
        key(account, "readable issued", "ACTIVE", "10", "synthetic-navigation-one", "1");
        key(account, "readable unreported", "ACTIVE", "10", "synthetic-navigation-two", null);
        UUID hidden = key(account, "another workspace", "ACTIVE", "10", "synthetic-navigation-hidden", "1");
        Workspace other = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Other navigation workspace " + UUID.randomUUID(), null));
        jdbc.update("update llm_api_keys set workspace_id=? where public_id=?", other.getId(), hidden);
        User actor = user(UserRole.ORG_VIEWER);
        grant(actor, Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ));
        String token = jwt.createAccessToken(actor);
        JsonNode detail = readToken(token, "/api/v1/admin/llm/accounts/" + account.publicId());
        assertThat(detail.get("boundKeyCount").asLong()).isEqualTo(3);
        assertThat(detail.get("allocation").get("committedKeyCount").asLong()).isEqualTo(3);
        JsonNode navigation = detail.get("keyNavigation");
        assertThat(navigation.get("population").asText()).isEqualTo("CURRENT_READABLE_KEYS");
        for (String[] pair : List.of(new String[] {"linked", "ALL"}, new String[] {"committed", "COMMITTED"},
                new String[] {"awaitingProvision", "AWAITING_PROVISION"}, new String[] {"usageUnreported", "USAGE_UNREPORTED"})) {
            JsonNode page = readToken(token, "/api/v1/admin/llm/keys?orgId=" + org.getPublicId()
                    + "&openrouterAccountId=" + account.publicId() + "&cohort=" + pair[1] + "&size=1");
            assertThat(navigation.get(pair[0]).get("state").asText()).isEqualTo("AVAILABLE");
            assertThat(page.get("totalElements").asLong()).isEqualTo(navigation.get(pair[0]).get("totalElements").asLong());
        }
        assertThat(navigation.get("linked").get("totalElements").asLong()).isEqualTo(2);
        assertThat(navigation.get("usageUnreported").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void modelPolicyPagesUseStoredFeatureAndExistingAliasMatchingWithoutAHiddenPolicyOracle() throws Exception {
        String model = "~vendor/exploration-pro:batch";
        catalogue.replaceListing(List.of(new OpenRouterClient.VendorModel(model, "Cached fixture model", 1000,
                BigDecimal.ONE, BigDecimal.ONE)), testClock.instant());
        Account account = account("stored policy relation");
        UUID wildcard = key(account, "wildcard alias", "ACTIVE", "10", "synthetic-policy-one", "0");
        UUID explicit = key(account, "explicit alias", "ACTIVE", "10", "synthetic-policy-two", "0");
        UUID concrete = key(account, "concrete provider", "ACTIVE", "10", "synthetic-policy-three", "0");
        UUID denied = key(account, "denied base alias", "ACTIVE", "10", "synthetic-policy-four", "0");
        UUID noFeature = key(account, "feature absent", "ACTIVE", "10", "synthetic-policy-five", "0");
        for (UUID id : List.of(wildcard, explicit, concrete, denied)) {
            jdbc.update("update llm_api_keys set passthrough_endpoints='[\"chat\"]'::jsonb where public_id=?", id);
        }
        jdbc.update("update llm_api_keys set credit_allowed_models='[\"*/*-pro\"]'::jsonb where public_id=?", wildcard);
        jdbc.update("update llm_api_keys set credit_allowed_models='[\"~vendor/exploration-pro\"]'::jsonb where public_id=?", explicit);
        jdbc.update("update llm_api_keys set credit_allowed_models='[\"vendor/*\"]'::jsonb where public_id=?", concrete);
        jdbc.update("update llm_api_keys set credit_denied_models='[\"vendor/exploration-pro\"]'::jsonb where public_id=?", denied);
        jdbc.update("update llm_api_keys set passthrough_endpoints='[]'::jsonb where public_id=?", noFeature);
        String base = "/api/v1/admin/llm/keys?orgId=" + org.getPublicId() + "&policyModel=" + model + "&policyFeature=chat&size=1&sort=NAME_ASC";
        JsonNode first = read(base + "&page=0");
        JsonNode second = read(base + "&page=1");
        JsonNode beyond = read(base + "&page=2");
        assertThat(first.get("totalElements").asLong()).isEqualTo(2);
        assertThat(first.get("content").get(0).get("id").asText()).isEqualTo(explicit.toString());
        assertThat(second.get("content").get(0).get("id").asText()).isEqualTo(wildcard.toString());
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(2);
        assertThat(beyond.get("content").isEmpty()).isTrue();
        User metadata = user(UserRole.ORG_VIEWER);
        grant(metadata, Set.of(InstitutionWork.LLM_METADATA_READ));
        mvc.perform(get(base).header("Authorization", "Bearer " + jwt.createAccessToken(metadata))).andExpect(status().isForbidden());
        verifyNoInteractions(provisioner, vendor);
    }

    private JsonNode impactAccount(JsonNode preview, UUID account) {
        for (JsonNode item : preview.get("accountImpact").get("accounts")) {
            if (item.get("accountId").asText().equals(account.toString())) { return item; }
        }
        throw new AssertionError("expected account projection is absent");
    }

    private void assertProjection(JsonNode impact, String before, String after, boolean overAllocated) {
        assertThat(impact.get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(impact.get("accounts").size()).isEqualTo(1);
        JsonNode account = impact.get("accounts").get(0);
        assertThat(account.get("before").get("remainingCommitment").decimalValue()).isEqualByComparingTo(before);
        assertThat(account.get("after").get("remainingCommitment").decimalValue()).isEqualByComparingTo(after);
        assertThat(account.get("overAllocated").asBoolean()).isEqualTo(overAllocated);
    }

    private void writeExample(String name, JsonNode value) throws Exception {
        String output = System.getProperty("allocation.examples");
        if (output == null) { return; }
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private JsonNode read(String url) throws Exception {
        return readToken(jwt.createAccessToken(reader), url);
    }

    private JsonNode readToken(String token, String url) throws Exception {
        return sendToken(token, get(url));
    }

    private JsonNode sendToken(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> bulkRequest(List<UUID> keys, Map<String, Object> limits) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetType", "LLM_KEY"); result.put("targetIds", keys);
        result.put("change", Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", limits));
        return result;
    }

    private JsonNode bulkPreview(User actor, List<UUID> keys, Map<String, Object> limits) throws Exception {
        return sendToken(jwt.createAccessToken(actor), post("/api/v1/admin/bulk-changes/preview")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bulkRequest(keys, limits))));
    }

    private void grant(User actor, Set<InstitutionWork> work) {
        var assignment = InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(), work,
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(workspace.getPublicId()))), 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("""
                insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)
                values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))
                on conflict(user_id,org_id) do update set role=excluded.role,permission_mode=excluded.permission_mode,
                    permission_payload=excluded.permission_payload,permission_revision=excluded.permission_revision
                """, actor.getId(), org.getId(), assignment.displayRole().name(), encoded.mode(), encoded.payload());
    }

    private void activate(Account account) {
        jdbc.update("""
                insert into openrouter_account_credentials(account_id,status,credential_enc,created_by,activated_at,verified_at)
                values(?,'ACTIVE',?,?,now(),now())
                """, account.id(), managementCipher.encrypt(account.publicId(), "synthetic-management-credential"), owner.getId());
    }

    private Map<String, Object> businessState(UUID key, Account account) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("key", jdbc.queryForMap("select credit_limit::text,credit_limit_reset::text,openrouter_account_id,"
                + "openrouter_usage::text,openrouter_usage_at::text,updated_at::text from llm_api_keys where public_id=?", key));
        state.put("account", jdbc.queryForMap("select updated_at::text,credits_total::text,credits_usage::text "
                + "from openrouter_accounts where id=?", account.id()));
        state.put("credential", jdbc.queryForList("select last_used_at::text,verified_at::text from openrouter_account_credentials where account_id=?", account.id()));
        state.put("generation", jdbc.queryForMap("select count(*) as rows,coalesce(max(generation),0) as generation from llm_gateway_state"));
        state.put("audit", jdbc.queryForObject("select count(*) from audit_logs", Long.class));
        for (String table : List.of("jobrunr_jobs", "jobrunr_recurring_jobs")) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table));
            state.put(table, exists ? jdbc.queryForObject("select count(*) from " + table, Long.class) : -1L);
        }
        return state;
    }

    private void daily(UUID key, long requests, long tokens, String cost, long priced) {
        jdbc.update("""
                insert into llm_usage_daily(day,key_id,public_model_name,requests,succeeded,input_tokens,output_tokens,
                    cost_usd,priced_requests,token_axis_requests)
                select ?,id,'exploration-model',?,?,?,0,?,?,? from llm_api_keys where public_id=?
                """, testClock.instant().atZone(ZoneOffset.ofHours(9)).toLocalDate(), requests, requests, tokens,
                cost == null ? BigDecimal.ZERO : new BigDecimal(cost), priced, requests, key);
    }

    private User user(UserRole role) {
        User value = new User("exploration-" + UUID.randomUUID() + "@example.com",
                "{test-no-login}", "Exploration fixture");
        value.setRole(role);
        value.setStatus(UserStatus.ACTIVE);
        value.setEmailVerifiedAt(Instant.now());
        return users.save(value);
    }

    private Account account(String name) {
        long id = jdbc.queryForObject("""
                insert into openrouter_accounts(org_id,name,created_by) values(?,?,?) returning id
                """, Long.class, org.getId(), name, owner.getId());
        UUID publicId = jdbc.queryForObject("select public_id from openrouter_accounts where id=?",
                UUID.class, id);
        return new Account(id, publicId);
    }

    private UUID key(Account account, String name, String state, String credit, String hash, String usage) {
        long request = jdbc.queryForObject("""
                insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name)
                values('LLM_API_KEY',?,?,?,'Exploration fixture',?) returning id
                """, Long.class, workspace.getId(), org.getId(), owner.getId(), name);
        jdbc.update("insert into llm_key_request_details(request_id,req_passthrough_endpoints) values(?,'[\"chat\"]')",
                request);
        return jdbc.queryForObject("""
                insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,status,daily_tokens,
                    credit_limit,openrouter_account_id,openrouter_key_hash,openrouter_key_enc,openrouter_usage,
                    openrouter_usage_at,created_by)
                values(?,?,?,?,'Exploration fixture',?::llm_api_key_status,1000,?,?,?,?,?,?,?) returning public_id
                """, UUID.class, workspace.getId(), org.getId(), request, name, state,
                new BigDecimal(credit), account == null ? null : account.id(), hash, hash == null ? null : "synthetic-ciphertext",
                usage == null ? null : new BigDecimal(usage),
                usage == null ? null : Timestamp.from(Instant.now()), owner.getId());
    }

    private void event(UUID key, Instant at, int tokens) {
        jdbc.update("""
                insert into llm_usage_events(event_id,key_id,public_model_name,budget_axis,status,
                    input_tokens,output_tokens,estimated,latency_ms,requested_at)
                select ?,id,'exploration-model','TOKEN','OK',?,0,false,1,? from llm_api_keys where public_id=?
                """, UUID.randomUUID().toString(), tokens, Timestamp.from(at), key);
    }

    private record Account(long id, UUID publicId) { }

    @TestConfiguration
    static class ClockBeans {
        @Bean @Primary MutableClock allocationExplorationClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private volatile Instant value = Instant.now();
        void set(Instant instant) { value = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
