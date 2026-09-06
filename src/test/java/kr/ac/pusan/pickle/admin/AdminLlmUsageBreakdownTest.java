package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.LlmUsageRollupService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The administrative usage surfaces added beside the holder's: one key, one
 * business account, and the platform-wide breakdown.
 *
 * <p>Most of what is asserted here is the difference between a missing number
 * and a zero one. Every defect this area has produced took the same shape: a
 * self-hosted request has no price at all, and an amount that reports it as
 * zero reads as "this was free" while ranking the consumer beside one that
 * genuinely spent nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmUsageBreakdownTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private LlmUsageRollupService rollupService;

    private Org orgA;
    private Org orgB;
    private User owner;
    private String sysToken;
    private String orgBAdminToken;
    private long accountA;
    private UUID accountAPublicId;
    private long accountB;
    private Key keyOne;
    private Key keyTwo;
    private Key keyOther;
    private String paidModel;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_usage_events");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        paidModel = "openai/paid-" + suffix;
        orgA = orgRepository.save(new Org("분해 기관 A " + suffix, null));
        orgB = orgRepository.save(new Org("분해 기관 B " + suffix, null));
        owner = user("breakdown-owner-" + suffix + "@pusan.ac.kr", UserRole.USER, null);
        sysToken = jwtService.createAccessToken(
                user("breakdown-sys-" + suffix + "@pusan.ac.kr", UserRole.SYS_ADMIN, null));
        orgBAdminToken = jwtService.createAccessToken(
                user("breakdown-orgb-" + suffix + "@pusan.ac.kr", UserRole.ORG_ADMIN,
                        orgB.getId()));

        Workspace workspaceA = workspaceRepository.save(
                new Workspace(WorkspaceKind.TEAM, "분해 A " + suffix, null));
        Workspace workspaceB = workspaceRepository.save(
                new Workspace(WorkspaceKind.TEAM, "분해 B " + suffix, null));
        accountA = account(orgA, "분해 사업 A " + suffix);
        accountAPublicId = SeedFixtures.publicId(jdbcTemplate, "openrouter_accounts", accountA);
        accountB = account(orgB, "분해 사업 B " + suffix);
        keyOne = key(orgA, workspaceA, "분해 키 하나 " + suffix, accountA, "[\"images\"]");
        keyTwo = key(orgA, workspaceA, "분해 키 둘 " + suffix, accountA, "[]");
        keyOther = key(orgB, workspaceB, "분해 남의 키 " + suffix, accountB, "[]");
    }

    @Test
    void aSelfHostedOnlyWindowReportsNoAmountRatherThanZero() throws Exception {
        // The defect this exists for. A self-hosted request has no dollar
        // figure at all, so a zero here would say the window was free.
        event(keyOne.id(), "pickle-general", "chat", null, 10, 20, hoursAgo(2));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.models[0].attributedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.trend.models[0].pricedRequests").value(0))
                .andExpect(jsonPath("$.trend.models[0].requests").value(1))
                .andExpect(jsonPath("$.costPoints[-1:].attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void aPricedRequestCarriesItsAmountAndItsCount() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.24200000", 5, 0, hoursAgo(2));
        event(keyOne.id(), paidModel, "images", null, 5, 0, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                // The catalogue has no row for a passed-through model, so a
                // join to decorate the name would drop exactly the traffic
                // that has a price on it.
                .andExpect(jsonPath("$.trend.models[0].modelName").value(paidModel))
                .andExpect(jsonPath("$.trend.models[0].requests").value(2))
                .andExpect(jsonPath("$.trend.models[0].pricedRequests").value(1))
                .andExpect(jsonPath("$.trend.models[0].attributedCostUsd").value(0.242))
                .andExpect(jsonPath("$.endpointKinds[0].endpoint").value("images"))
                .andExpect(jsonPath("$.endpointKinds[0].requests").value(2))
                .andExpect(jsonPath("$.endpointKinds[0].pricedRequests").value(1));
    }

    @Test
    void aFallbackAppearsAndAMatchingAnswerDoesNot() throws Exception {
        // The gateway writes the served name only when it differs from the
        // model it sent, so presence is the finding. Nothing on this side
        // compares it against the public name, which is deliberately not the
        // upstream one.
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        jdbcTemplate.update("""
                update llm_usage_events set served_model_name = 'other-upstream'
                 where key_id = ?
                """, keyOne.id());
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedModels.length()").value(1))
                .andExpect(jsonPath("$.servedModels[0].servedModelName").value("other-upstream"))
                .andExpect(jsonPath("$.servedModels[0].requests").value(1));
    }

    @Test
    void aRouteRecordedBeforeTheColumnExistedIsItsOwnBucketRatherThanOther() throws Exception {
        event(keyOne.id(), "pickle-general", null, null, 1, 1, hoursAgo(2));
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointKinds.length()").value(2))
                .andExpect(jsonPath("$.endpointKinds[?(@.endpoint == 'chat')].requests")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.endpointKinds[?(@.endpoint == null)].requests")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void aKeyOutsideTheReadersInstitutionsIsAnswatchedAsMissing() throws Exception {
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        rollupService.refresh();

        adminKeyUsage(orgBAdminToken, keyOne.publicId()).andExpect(status().isNotFound());
    }

    @Test
    void anAccountSeriesKeepsEveryDayEvenWhenAnotherAccountHasTrafficThatDay()
            throws Exception {
        // The join has to carry the account filter into it. Filtering after a
        // plain join to the rollup drops the whole day whenever some other
        // account is the only one with a bucket on it, and the gap then reads
        // as "no data reached us".
        event(keyOther.id(), paidModel, "images", "1.00000000", 1, 1, hoursAgo(2));
        rollupService.refresh();

        accountUsage(sysToken, accountAPublicId, 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.requests").value(0))
                .andExpect(jsonPath("$.attributedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.keysUsed").value(0))
                .andExpect(jsonPath("$.keysLinked").value(2));
    }

    @Test
    void anAccountAttributesOnlyItsOwnKeysAndSaysHowManyWerePriced() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.50000000", 2, 2, hoursAgo(2));
        event(keyTwo.id(), "pickle-general", "chat", null, 3, 3, hoursAgo(2));
        event(keyOther.id(), paidModel, "images", "9.00000000", 1, 1, hoursAgo(2));
        rollupService.refresh();

        accountUsage(sysToken, accountAPublicId, 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(2))
                .andExpect(jsonPath("$.pricedRequests").value(1))
                .andExpect(jsonPath("$.attributedCostUsd").value(0.5))
                .andExpect(jsonPath("$.keysUsed").value(2))
                .andExpect(jsonPath("$.keys.length()").value(2))
                // The unpriced key carries no amount rather than a zero one.
                .andExpect(jsonPath("$.keys[?(@.keyName =~ /.*둘.*/)].attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void theBreakdownCountsGrantedKeysApartFromKeysThatUsedTheCapability() throws Exception {
        // One capability opens two routes, and the count has to follow both.
        event(keyOne.id(), paidModel, "images", "0.10000000", 1, 1, hoursAgo(2));
        event(keyOne.id(), paidModel, "images_models", null, 0, 0, hoursAgo(3));
        rollupService.refresh();

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.passthroughGrants.length()").value(2))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].grantedKeys")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].usedKeys")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].requests")
                        .value(org.hamcrest.Matchers.contains(2)))
                // Granted to nobody and used by nobody is a zero row, not an
                // absent one: an absent row reads as "no data".
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'embeddings')]"
                        + ".grantedKeys")
                        .value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    void theConsumerRowsCarryAnAmountOnlyWhereSomethingWasPriced() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.75000000", 1, 1, hoursAgo(2));
        event(keyTwo.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        rollupService.refresh();

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality.pricedRequests").value(1))
                .andExpect(jsonPath("$.quality.endpointRecordedRequests").value(2))
                .andExpect(jsonPath("$.breakdown.models.length()").value(2))
                .andExpect(jsonPath(
                        "$.breakdown.models[?(@.modelName == '%s')].attributedCostUsd"
                                .formatted(paidModel))
                        .value(org.hamcrest.Matchers.contains(0.75)))
                .andExpect(jsonPath(
                        "$.breakdown.models[?(@.modelName == 'pickle-general')]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())));
    }

    private ResultActions adminKeyUsage(String token, UUID keyId) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/keys/" + keyId + "/usage")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions accountUsage(String token, UUID accountId, int days) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/accounts/" + accountId
                        + "/usage?days=" + days)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions platformUsage(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/usage?" + query)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private Instant hoursAgo(int hours) {
        LocalDate today = ClockConfig.todayKst(java.time.Clock.systemUTC());
        return today.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
                .minusSeconds(hours * 3600L);
    }

    private void event(long keyId, String model, String endpoint, String costUsd,
            int inputTokens, int outputTokens, Instant requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events
                       (event_id, key_id, public_model_name, endpoint, cost_usd, status,
                        input_tokens, output_tokens, estimated, latency_ms, requested_at)
                values (?, ?, ?, ?, ?::numeric, 'OK', ?, ?, false, 1, ?)
                """, UUID.randomUUID().toString(), keyId, model, endpoint, costUsd,
                inputTokens, outputTokens, Timestamp.from(requestedAt));
    }

    private long account(Org org, String name) {
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?) returning public_id
                """, UUID.class, org.getId(), name, owner.getId());
        return SeedFixtures.internalId(jdbcTemplate, "openrouter_accounts", publicId);
    }

    private Key key(Org org, Workspace workspace, String name, long accountId,
            String passthrough) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '분해 시험', ?)
                returning id
                """, Long.class, workspace.getId(), org.getId(), owner.getId(), name);
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, token_hash, token_prefix,
                        status, credit_limit, quota_exhausted, openrouter_account_id,
                        passthrough_endpoints, created_by)
                values (?, ?, ?, ?, ?, 'pickle-brk', 'ACTIVE', 10, false, ?, ?::jsonb, ?)
                returning public_id
                """, UUID.class, workspace.getId(), org.getId(), requestId, name,
                UUID.randomUUID().toString(), accountId, passthrough, owner.getId());
        return new Key(SeedFixtures.internalId(jdbcTemplate, "llm_api_keys", publicId),
                publicId);
    }

    private User user(String email, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", email.substring(0, 12));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(role);
        User saved = userRepository.save(user);
        if (orgId != null) {
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        }
        return saved;
    }

    private record Key(long id, UUID publicId) {
    }
}
