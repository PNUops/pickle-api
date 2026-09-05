package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.hamcrest.Matchers;
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
import tools.jackson.databind.ObjectMapper;

/**
 * What one key is told it may call.
 *
 * <p>The assertions that matter are the ones about narrowing and about
 * silence. A key restricted to some models must not be shown the rest; an
 * allow-list entry the listing cannot satisfy must be named rather than
 * quietly dropped; and the response must never carry the upstream a
 * self-served model actually runs on, which is the one fact this platform
 * keeps to itself so the model behind a public name can change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmKeyModelsTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User wsOwner;
    private User keyOwner;
    private User outsider;
    private String wsOwnerToken;
    private String keyOwnerToken;
    private String outsiderToken;
    private long orgId;
    private long workspaceId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        wsOwner = ensureUser("llmmodels.wsowner@pusan.ac.kr", "모델워크스페이스소유자");
        keyOwner = ensureUser("llmmodels.keyowner@pusan.ac.kr", "모델키소유자");
        outsider = ensureUser("llmmodels.outsider@pusan.ac.kr", "모델외부인");
        wsOwnerToken = jwtService.createAccessToken(wsOwner);
        keyOwnerToken = jwtService.createAccessToken(keyOwner);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        accountId = 0;
        workspaceId = createTeam("LLM 모델 목록 테스트 "
                + UUID.randomUUID().toString().substring(0, 8));
        addMember(keyOwner.getEmail());
        jdbcTemplate.update("delete from openrouter_catalogue_model");
        jdbcTemplate.update("delete from openrouter_catalogue_state");
        seedSelfServedModels();
    }

    @Test
    void aNonMemberCannotTellTheKeyFromAMissingOne() throws Exception {
        UUID keyId = createKey("차단 키", "0.00", null, null);
        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void aKeyWithNoBudgetStillSeesWhatItCouldAskFor() throws Exception {
        seedCatalogue(Instant.now());
        UUID keyId = createKey("한도 없는 키", "0.00", null, null);

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("NONE"))
                // The list is the point: a holder with no budget is the one who
                // most needs to know what a request would be for.
                .andExpect(jsonPath("$.paid.models", Matchers.not(Matchers.empty())))
                // Only the row a key can actually reach. Three are excluded and
                // each for its own reason: the disabled one, the restricted one
                // (no key reaches it, because the document this platform sends
                // carries no per-key model grants), and the money-axis one,
                // which is a catalogue row rather than something this half of
                // the response is about. Without a money-axis row seeded, that
                // filter could be deleted and every assertion here would stay
                // green.
                .andExpect(jsonPath("$.selfServed.length()").value(1))
                .andExpect(jsonPath("$.selfServed[0].name").value("pickle-general"))
                .andExpect(jsonPath("$.selfServed[*].name",
                        Matchers.not(Matchers.hasItem("pickle-billed"))))
                // Zero in the column means no cap; the screen says nothing
                // rather than saying zero.
                .andExpect(jsonPath("$.selfServed[0].maxInputTokens").doesNotExist());
    }

    @Test
    void aGrantedBudgetStillBeingConnectedSaysSo() throws Exception {
        seedCatalogue(Instant.now());
        UUID keyId = createKey("연결 전 키", "5.00", null, null);

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("PENDING"));
    }

    @Test
    void anUnrestrictedKeyReachesTheWholeListing() throws Exception {
        seedCatalogue(Instant.now());
        UUID keyId = createKey("제한 없는 키", "5.00", "hash-unrestricted", null);

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.paid.models.length()").value(3))
                // Vendor order first, then input price. The cheapest row in the
                // listing belongs to no ranked vendor and must not lead.
                .andExpect(jsonPath("$.paid.models[0].id").value("openai/gpt-5.6-luna"))
                .andExpect(jsonPath("$.paid.models[2].id").value("tinyvendor/cheap-8b"))
                // Per-token becomes per-million so the number is readable.
                .andExpect(jsonPath("$.paid.models[0].promptPricePerMillion").value(0.2))
                .andExpect(jsonPath("$.paid.freshness").doesNotExist())
                .andExpect(jsonPath("$.paid.catalogFreshness").value("FRESH"));
    }

    @Test
    void anAllowListNarrowsTheListingAndNamesWhatItCouldNotFind() throws Exception {
        seedCatalogue(Instant.now());
        UUID keyId = createKey("허용 목록 키", "5.00", "hash-listed",
                "[\"openai/*\",\"vendor/gone\"]");

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("LISTED"))
                .andExpect(jsonPath("$.paid.models.length()").value(2))
                .andExpect(jsonPath("$.paid.models[*].id",
                        Matchers.everyItem(Matchers.startsWith("openai/"))))
                // A pattern the listing cannot satisfy is said out loud. Left
                // silent, a withdrawn model looks like an empty allow list.
                .andExpect(jsonPath("$.paid.unmatchedPatterns.length()").value(1))
                .andExpect(jsonPath("$.paid.unmatchedPatterns[0]").value("vendor/gone"))
                .andExpect(jsonPath("$.paid.allowedPatterns.length()").value(2));
    }

    /**
     * A denied model must not be listed as callable. This is the assertion the
     * whole deny list rests on from the screen's side: the gateway refuses the
     * call regardless, so leaving it out here does not let anybody spend
     * anything — what it does is show a reviewer a model the key cannot use,
     * and reviewers approve against this listing. A wrong row here becomes a
     * decision, not just a wrong pixel.
     *
     * <p>The two lists overlap on purpose: {@code openai/gpt-4o} is inside
     * {@code openai/*} and denied, so it can only be absent because the deny
     * list removed it. The surviving sibling is asserted by name as well — a
     * test that only says a row is missing also passes when the listing is
     * empty for some unrelated reason, and the denied name must be one the
     * fixture actually seeds or the assertion is true no matter what the
     * server does.
     */
    @Test
    void aDeniedModelIsNotListedEvenWhenTheAllowListCoversIt() throws Exception {
        seedCatalogue(Instant.now());
        UUID keyId = createKey("차단 목록 키", "5.00", "hash-denied",
                "[\"openai/*\"]", "[\"openai/gpt-4o\"]");

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.models[*].id",
                        Matchers.not(Matchers.hasItem("openai/gpt-4o"))))
                .andExpect(jsonPath("$.paid.models[*].id",
                        Matchers.hasItem("openai/gpt-5.6-luna")))
                .andExpect(jsonPath("$.paid.models.length()").value(1));
    }

    /**
     * The deny list narrows on its own, with no allow list to hide behind. An
     * empty allow list means "every model", so this is the case where the only
     * thing standing between the catalogue and the screen is the deny list.
     */
    @Test
    void aDenyListNarrowsAnOtherwiseUnrestrictedKey() throws Exception {
        seedCatalogue(Instant.now());
        UUID unrestricted = createKey("차단 없는 키", "5.00", "hash-open", null);
        UUID denied = createKey("차단만 있는 키", "5.00", "hash-denyonly", null,
                "[\"openai/*\"]");

        int all = countModels(unrestricted);
        int left = countModels(denied);
        assertThat(left)
                .describedAs("the deny list must remove the openai rows, not nothing")
                .isLessThan(all);

        mockMvc.perform(get(url(denied)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.models[*].id",
                        Matchers.not(Matchers.hasItem(Matchers.startsWith("openai/")))));
    }

    /**
     * A key narrowed only by a deny list is LISTED, not UNRESTRICTED.
     *
     * <p>UNRESTRICTED asserts that nothing but money bounds what the key may
     * call, which is false the moment somebody refuses a model. Answering from
     * the allow list alone would leave the label and the list below it saying
     * opposite things on the same screen, and a reviewer approves against that
     * screen — so the summary being wrong is worse than it looks, because the
     * summary is the part people read.
     */
    @Test
    void aKeyNarrowedOnlyByADenyListIsNotCalledUnrestricted() throws Exception {
        seedCatalogue(Instant.now());
        UUID denyOnly = createKey("차단만 있는 라벨 키", "5.00", "hash-denylabel", null,
                "[\"openai/*\"]");
        UUID neither = createKey("두 목록 모두 빈 키", "5.00", "hash-nolabel", null);

        mockMvc.perform(get(url(denyOnly)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("LISTED"));
        // The other half of the rule: with neither list set the label is still
        // UNRESTRICTED, so this is a narrowing test rather than one that just
        // stopped the value from ever appearing.
        mockMvc.perform(get(url(neither)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.access").value("UNRESTRICTED"));
    }

    private int countModels(UUID keyId) throws Exception {
        String body = mockMvc.perform(get(url(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("paid").get("models").size();
    }

    @Test
    void aListingThatNeverArrivedIsSaidPlainlyRatherThanShownEmpty() throws Exception {
        UUID keyId = createKey("캐시 없는 키", "5.00", "hash-unknown", null);

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.catalogFreshness").value("UNKNOWN"))
                .andExpect(jsonPath("$.paid.catalogObservedAt").doesNotExist())
                .andExpect(jsonPath("$.paid.models").isEmpty())
                // The self-served half comes from this platform's own rows, so
                // a vendor that never answered cannot take it down with it.
                .andExpect(jsonPath("$.selfServed", Matchers.not(Matchers.empty())));
    }

    @Test
    void anOldListingIsShownWithItsAge() throws Exception {
        seedCatalogue(Instant.now().minus(9, ChronoUnit.HOURS));
        UUID keyId = createKey("낡은 캐시 키", "5.00", "hash-stale", null);

        mockMvc.perform(get(url(keyId)).header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.catalogFreshness").value("STALE"))
                .andExpect(jsonPath("$.paid.models", Matchers.not(Matchers.empty())));
    }

    /**
     * The operational half of the cache is the administrator's. A student
     * reading this screen learns that the listing is old, never why the fetch
     * failed or how many times.
     */
    @Test
    void neverCarriesTheUpstreamOrTheVendorFailure() throws Exception {
        seedCatalogue(Instant.now());
        jdbcTemplate.update(
                "update openrouter_catalogue_state set last_error = ?, consecutive_failures = 4",
                "vendor said 502 at the edge");
        UUID keyId = createKey("누설 없는 키", "5.00", "hash-clean", null);

        String body = mockMvc.perform(get(url(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("lastError"))))
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("consecutiveFailures"))))
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("vendor said 502"))))
                .andReturn().getResponse().getContentAsString();
        // The upstream a self-served name runs on is the fact that lets the
        // model behind it change without touching student code.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("upstreamModel").doesNotContain("upstream_model")
                .doesNotContain("qwen");
    }

    private String url(UUID keyId) {
        return "/api/v1/llm-keys/" + keyId + "/models";
    }

    /**
     * Catalogue rows come from an operational script rather than a migration,
     * so a test database has none until one is put there. Three shapes: the
     * public one, a disabled one, and a restricted one — the last two must not
     * reach the response.
     */
    private void seedSelfServedModels() {
        jdbcTemplate.update("delete from llm_models");
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model, visibility,
                                        budget_axis, max_input_tokens, max_output_tokens, enabled)
                values ('pickle-general', 'dgx', 'qwen3.6-35b-a3b-nvfp4', 'PUBLIC',
                        'TOKEN', 0, 0, true),
                       ('pickle-retired', 'dgx', 'qwen-old', 'PUBLIC',
                        'TOKEN', 0, 0, false),
                       ('pickle-internal', 'dgx', 'qwen-secret', 'RESTRICTED',
                        'TOKEN', 0, 0, true),
                       ('pickle-billed', 'passthrough', 'openai/gpt-4o', 'PUBLIC',
                        'CREDIT', 0, 0, true)
                """);
    }

    /** Three rows: two from a ranked vendor, one cheaper from an unranked one. */
    private void seedCatalogue(Instant observedAt) {
        jdbcTemplate.update("""
                insert into openrouter_catalogue_state (id, last_attempt_at, last_success_at,
                                                        last_model_count)
                values (true, ?, ?, 3)
                on conflict (id) do update set last_attempt_at = excluded.last_attempt_at,
                    last_success_at = excluded.last_success_at, last_error = null,
                    consecutive_failures = 0
                """, java.sql.Timestamp.from(observedAt), java.sql.Timestamp.from(observedAt));
        insertModel("openai/gpt-5.6-luna", "GPT-5.6 Luna", "0.0000002", "0.0000012", 1050000);
        insertModel("openai/gpt-4o", "GPT-4o", "0.0000025", "0.00001", 128000);
        insertModel("tinyvendor/cheap-8b", "Cheap 8B", "0.00000004", "0.00000005", 8192);
    }

    private void insertModel(String id, String name, String prompt, String completion, int ctx) {
        jdbcTemplate.update("""
                insert into openrouter_catalogue_model (model_id, display_name, context_length,
                                                        prompt_price, completion_price)
                values (?, ?, ?, ?::numeric, ?::numeric)
                """, id, name, ctx, prompt, completion);
    }

    /** A business account, required the moment a key carries a positive budget. */
    private long ensureAccount() {
        if (accountId == 0) {
            accountId = jdbcTemplate.queryForObject("""
                    insert into openrouter_accounts (org_id, name, created_by)
                    values (?, ?, ?)
                    returning id
                    """, Long.class, orgId, "모델 목록 시험 사업 " + UUID.randomUUID(),
                    wsOwner.getId());
        }
        return accountId;
    }

    private UUID createKey(String name, String creditLimit, String openrouterKeyHash,
            String allowedModels) {
        return createKey(name, creditLimit, openrouterKeyHash, allowedModels, null);
    }

    private UUID createKey(String name, String creditLimit, String openrouterKeyHash,
            String allowedModels, String deniedModels) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, left(?, 100))
                returning id
                """, Long.class, workspaceId, orgId, keyOwner.getId(), name + " 용도", name);
        String hash = (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
        boolean funded = new java.math.BigDecimal(creditLimit).signum() > 0;
        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          token_hash, token_prefix, status, created_by,
                                          credit_limit, openrouter_account_id,
                                          openrouter_key_hash, openrouter_key_enc,
                                          credit_allowed_models,
                                          credit_denied_models)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::numeric, ?, ?, ?,
                        coalesce(?::jsonb, '[]'::jsonb),
                        coalesce(?::jsonb, '[]'::jsonb))
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, name + " 용도", hash,
                "pickle-" + hash.substring(0, 6), keyOwner.getId(), creditLimit,
                funded ? ensureAccount() : null,
                openrouterKeyHash,
                // The hash and its ciphertext are one fact: the schema refuses
                // a key that carries one without the other.
                openrouterKeyHash == null ? null : "enc-" + openrouterKeyHash,
                allowedModels,
                deniedModels);
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, user_id, role)
                values ('LLM_API_KEY', ?, 'USER', ?, 'OWNER'::resource_role)
                """, keyId, keyOwner.getId());
        return jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, keyId);
    }

    private long createTeam(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + wsOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID publicId = UUID.fromString(objectMapper.readTree(body).get("id").asString());
        return jdbcTemplate.queryForObject(
                "select id from workspaces where public_id = ?", Long.class, publicId);
    }

    private void addMember(String email) throws Exception {
        UUID workspacePublicId = jdbcTemplate.queryForObject(
                "select public_id from workspaces where id = ?", UUID.class, workspaceId);
        mockMvc.perform(post("/api/v1/workspaces/" + workspacePublicId + "/members")
                        .header("Authorization", "Bearer " + wsOwnerToken)
                        .header(ReauthTestSupport.HEADER, ReauthTestSupport.seededReauthFor(
                                jdbcTemplate, jwtService, wsOwnerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", "MEMBER"))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
