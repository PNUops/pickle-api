package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.JsonPathResultMatchers;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM gateway surface: the dedicated chain (static bearer with rotation
 * overlap, source pin, per-sub-path buckets and caps) ordered ahead of the
 * fail-closed {@code /internal/**} catch-all; sync semantics (unchanged
 * answers are the bare generation with NO document members; a changed answer
 * carries {@code models} and {@code keys} together; a reported generation
 * above ours raises ours instead of being discarded); usage ingest (per-event
 * rejection never a 4xx, event-id dedup, unattributed events kept); and the
 * separation of the bodies channel from the usage table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmGatewayEndpointTest {

    /** The default allowed source (the LLM gateway LXC). */
    private static final String SOURCE = "172.30.1.40";
    private static final String TOKEN = "test-llm-gateway-token";
    private static final String PREVIOUS_TOKEN = "old-llm-gateway-token";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-gateway.token", () -> TOKEN);
        registry.add("pickle.llm-gateway.previous-token", () -> PREVIOUS_TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private kr.ac.pusan.pickle.common.crypto.CredentialCipher credentialCipher;

    @BeforeEach
    void resetGatewayState() {
        // The counter is a single shared row and the usage/key/model tables
        // are this suite's own; all start empty so each test sees exactly the
        // state it arranges. (audit_logs is append-only and stays.)
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from llm_models");
        jdbcTemplate.update("delete from llm_upstream_state");
        jdbcTemplate.update("delete from llm_gateway_state");
    }

    // ── chain: auth, ordering, caps ─────────────────────────────────────────

    @Test
    void wrongTokenAnswers401() throws Exception {
        syncFrom(SOURCE, "not-the-token", poll(0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void missingTokenAnswers401() throws Exception {
        mockMvc.perform(post("/internal/llm/sync").with(remoteAddr(SOURCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(poll(0))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previousTokenIsAcceptedDuringRotationOverlap() throws Exception {
        syncFrom(SOURCE, PREVIOUS_TOKEN, poll(0)).andExpect(status().isOk());
    }

    @Test
    void wrongSourceAnswers403EvenWithTheRightToken() throws Exception {
        syncFrom("203.0.113.99", TOKEN, poll(0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void llmChainIsReachedAheadOfTheInternalCatchAll() throws Exception {
        // The broad /internal/** chain pins the sshgw LXC (172.30.1.30) and
        // its own token, so a 200 from the gateway's source with the gateway's
        // token proves the dedicated chain claimed the path first.
        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk());
        // Every other /internal path still lands in the fail-closed catch-all:
        // the gateway's source and token open nothing there.
        mockMvc.perform(post("/internal/llm-other").with(remoteAddr(SOURCE))
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/sshgw/route").with(remoteAddr(SOURCE))
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // Unknown sub-paths of the LLM surface itself are refused in-chain.
        mockMvc.perform(post("/internal/llm/other").with(remoteAddr(SOURCE))
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncBodyOverTheCapAnswers413() throws Exception {
        Map<String, Object> body = poll(0);
        body.put("agentVersion", "x".repeat(70_000)); // sync cap is 64 KiB
        syncFrom(SOURCE, TOKEN, body).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rateBucketsAreOnePerSubPath() throws Exception {
        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk());
        usage(Map.of("events", List.of())).andExpect(status().isOk());
        bodies(Map.of("records", List.of())).andExpect(status().isOk());
        List<String> scopes = jdbcTemplate.queryForList("""
                select distinct scope from auth_rate_limits
                 where subject = 'gateway' and scope like 'llm_%'
                """, String.class);
        assertThat(scopes).contains("llm_sync", "llm_usage", "llm_bodies");
    }

    // ── sync semantics ──────────────────────────────────────────────────────

    @Test
    void firstPollCreatesTheStateRowAndServesTheFullDocument() throws Exception {
        assertThat(stateRowCount()).isZero(); // no migration seeds the row
        syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.serviceEnabled").value(true))
                .andExpect(jsonPath("$.models").isArray())
                .andExpect(jsonPath("$.keys").isArray());
        assertThat(stateRowCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select applied_generation, supported_format, in_flight, last_contact_at
                  from llm_gateway_state
                """);
        assertThat(((Number) row.get("applied_generation")).longValue()).isZero();
        assertThat(((Number) row.get("supported_format")).intValue()).isEqualTo(1);
        assertThat(row.get("last_contact_at")).isNotNull();
    }

    @Test
    void unchangedPollAnswersTheBareGenerationAndNothingElse() throws Exception {
        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk()); // creates gen 1
        String body = syncFrom(SOURCE, TOKEN, poll(1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Raw-JSON assertion on purpose: models/keys must be ABSENT together
        // (one alone is a violation, an empty array is a real state) and
        // serviceEnabled must not leak into the unchanged shape.
        assertThat(body).isEqualTo("{\"generation\":1}");
    }

    @Test
    void changedPollCarriesModelsAndKeysTogether() throws Exception {
        KeyFixture key = newKey("doc");
        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk()); // creates gen 1
        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.serviceEnabled").value(true))
                .andExpect(jsonPath("$.keys[0].keyId").value(key.publicId().toString()))
                .andExpect(jsonPath("$.keys[0].tokenHash").value(key.tokenHash()))
                .andExpect(jsonPath("$.keys[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.keys[0].quotaExhausted").value(false))
                .andExpect(jsonPath("$.keys[0].recordBodies").value(false))
                .andExpect(jsonPath("$.keys[0].allowedModels").isArray())
                // The money fence rides alongside the curation list and stays
                // separate from it. A later round that merges the two would
                // silently lock every fenced key out of self-serving models.
                .andExpect(jsonPath("$.keys[0].creditAllowedModels").isArray())
                .andExpect(jsonPath("$.keys[0].creditAllowedModels.length()").value(0))
                // Its opposite travels beside it, and it is an EMPTY ARRAY
                // rather than an omission: the gateway ignores a key it does
                // not know, so a missing member and "nothing is blocked" would
                // be the same document, and only one of them is true.
                .andExpect(jsonPath("$.keys[0].creditDeniedModels").isArray())
                .andExpect(jsonPath("$.keys[0].creditDeniedModels.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        // Both members present — models as an EMPTY ARRAY (a real state: no
        // catalogue rows exist yet), never omitted alongside a present keys.
        assertThat(body).contains("\"models\":[]").contains("\"keys\":[");
        // No limits configured on the key: the member drops out entirely.
        assertThat(body).doesNotContain("\"limits\"");
    }

    @Test
    void configuredLimitsAndExpiryAppearOnTheKeyEntry() throws Exception {
        KeyFixture key = newKey("limits");
        jdbcTemplate.update("""
                update llm_api_keys set rpm = 20, tpm = 20000,
                       expires_at = now() + interval '30 days' where id = ?
                """, key.id());
        syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].limits.rpm").value(20))
                .andExpect(jsonPath("$.keys[0].limits.tpm").value(20000))
                .andExpect(jsonPath("$.keys[0].limits.concurrency").doesNotExist())
                .andExpect(jsonPath("$.keys[0].expiresAt").exists());
    }

    @Test
    void passthroughRefAndBudgetAxisTravelOnTheDocument() throws Exception {
        // passthroughRef derives from the llm_upstreams registry row flagged
        // as the passthrough target (the V87 seed), and every model row says
        // which budget axis governs it.
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model, budget_axis)
                values ('pnu-doc-test', 'openai', 'x', 'TOKEN')
                """);
        syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passthroughRef").value("openrouter"))
                .andExpect(jsonPath("$.models[0].publicName").value("pnu-doc-test"))
                .andExpect(jsonPath("$.models[0].budgetAxis").value("TOKEN"));
    }

    @Test
    void disabledPassthroughRowDropsTheMember() throws Exception {
        // Absence is a real state ("no passthrough"), so a disabled registry
        // row must remove the member entirely rather than send null.
        jdbcTemplate.update("update llm_upstreams set enabled = false where passthrough");
        try {
            String body = syncFrom(SOURCE, TOKEN, poll(0))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("passthroughRef");
        } finally {
            jdbcTemplate.update("update llm_upstreams set enabled = true where passthrough");
        }
    }

    // Both money fences reach the gateway as stored, each on its own field:
    // allowedModels stays empty, because filling that one would lock the key out
    // of self-serving models as a side effect.
    //
    // The two lists carry different values on purpose. They are arrays of the
    // same shape on adjacent members, so a transposed pair produces a
    // well-formed document that inverts the fence, and the gateway would apply
    // it without complaint.
    @Test
    void bothCreditModelListsTravelInTheDocument() throws Exception {
        // A positive money limit needs an account binding since the legacy source
        // was retired, so the fence rides a properly bound key.
        long account = insertOpenrouterAccount();
        KeyFixture fenced = newKey("fenced");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, credit_allowed_models = ?::jsonb, "
                + "credit_denied_models = ?::jsonb where id = ?",
                account, "[\"openai/*\", \"anthropic/claude-sonnet-4\"]",
                "[\"openai/*-pro\"]", fenced.id());

        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk());
        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[?(@.keyId=='" + fenced.publicId()
                        + "')].creditAllowedModels[0]").value("openai/*"))
                .andExpect(jsonPath("$.keys[?(@.keyId=='" + fenced.publicId()
                        + "')].creditAllowedModels[1]").value("anthropic/claude-sonnet-4"))
                .andExpect(jsonPath("$.keys[?(@.keyId=='" + fenced.publicId()
                        + "')].creditDeniedModels[0]").value("openai/*-pro"))
                .andExpect(jsonPath("$.keys[?(@.keyId=='" + fenced.publicId()
                        + "')].creditDeniedModels[1]").doesNotExist())
                .andExpect(jsonPath("$.keys[?(@.keyId=='" + fenced.publicId()
                        + "')].allowedModels[0]").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // The gateway's decoder ignores a key it does not know, so a renamed
        // member arrives as "nothing is blocked" with nothing to say so. Pin
        // the spelling itself, not only the value behind it.
        assertThat(body).contains("\"creditDeniedModels\":[\"openai/*-pro\"]");
    }

    /**
     * What actually happens to a stored list this side cannot read: the gateway
     * is served an empty one and goes on serving the key.
     *
     * <p>Worth pinning because the obvious guess is the opposite. The gateway
     * does drop a key whose document it cannot parse — but it never gets the
     * chance here, because the document is built through the same lenient
     * reader, so the malformed value is already an empty array by the time it is
     * serialized. The two sides do not disagree; they agree on "nothing is
     * blocked", which is the wrong answer arrived at quietly from both ends. The
     * WARN log is the only thing that marks it.
     *
     * <p>Reaching this state takes a write from outside the application: the
     * column is not null and V109's CHECK requires a JSON array. The constraint
     * is dropped and restored here for that reason, which is also why this is a
     * documented behaviour rather than a live hole.
     */
    @Test
    void anUnreadableStoredDenyListReachesTheGatewayAsEmpty() throws Exception {
        long account = insertOpenrouterAccount();
        KeyFixture corrupt = newKey("corrupt-deny");
        jdbcTemplate.update("alter table llm_api_keys "
                + "drop constraint llm_api_keys_credit_denied_models_check");
        try {
            jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                    + "openrouter_account_id = ?, credit_denied_models = ?::jsonb where id = ?",
                    account, "{\"not\":\"an array\"}", corrupt.id());

            syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk());
            String body = syncFrom(SOURCE, TOKEN, poll(0))
                    .andExpect(status().isOk())
                    // The key is served, not dropped.
                    .andExpect(jsonPath("$.keys[?(@.keyId=='" + corrupt.publicId()
                            + "')].keyId").exists())
                    .andReturn().getResponse().getContentAsString();
            // And its fence arrives empty, which reads as "blocks nothing".
            assertThat(body).contains("\"creditDeniedModels\":[]");
        } finally {
            jdbcTemplate.update("update llm_api_keys set credit_denied_models = '[]'::jsonb "
                    + "where id = ?", corrupt.id());
            jdbcTemplate.update("alter table llm_api_keys "
                    + "add constraint llm_api_keys_credit_denied_models_check "
                    + "check (llm_credit_model_patterns_valid(credit_denied_models))");
        }
    }

    @Test
    void upstreamCredentialTravelsOnlyForActiveFundedProvisionedKeys() throws Exception {
        // The one usable secret in the document: present exactly when the key
        // is ACTIVE, its money budget positive, and its OpenRouter key
        // provisioned. Every other state omits the member, and omission is
        // what closes the commercial axis for the key.
        // A money budget always names the account that funds it, so the
        // grant and the binding move together in one statement.
        long account = insertOpenrouterAccount();
        KeyFixture funded = insertKey("funded", LlmApiKeyTokens.hash(LlmApiKeyTokens.newToken()),
                "pickle-fu", "ACTIVE");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, "
                + "openrouter_key_hash = 'hash-funded', openrouter_key_enc = ? where id = ?",
                account, credentialCipher.encrypt("sk-or-funded"), funded.id());
        KeyFixture unfunded = insertKey("unfunded",
                LlmApiKeyTokens.hash(LlmApiKeyTokens.newToken()), "pickle-un", "ACTIVE");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 0, "
                + "openrouter_key_hash = 'hash-unfunded', openrouter_key_enc = ? where id = ?",
                credentialCipher.encrypt("sk-or-unfunded"), unfunded.id());
        KeyFixture unprovisioned = insertKey("unprovisioned",
                LlmApiKeyTokens.hash(LlmApiKeyTokens.newToken()), "pickle-up", "ACTIVE");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ? where id = ?", account, unprovisioned.id());
        KeyFixture revoked = insertKey("revoked-funded",
                LlmApiKeyTokens.hash(LlmApiKeyTokens.newToken()), "pickle-rv", "ACTIVE");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, "
                + "openrouter_key_hash = 'hash-revoked', openrouter_key_enc = ?, "
                + "status = 'REVOKED', revoked_at = now() "
                + "where id = ?", account, credentialCipher.encrypt("sk-or-revoked"),
                revoked.id());

        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.keys[?(@.keyId=='%s')].upstreamCredentials.openrouter"
                                .formatted(funded.publicId()))
                        .value("sk-or-funded"))
                .andReturn().getResponse().getContentAsString();
        // The funded key's secret is the only one anywhere in the document —
        // decrypted, keyed by the upstream ref, and never a ciphertext.
        assertThat(body).contains("sk-or-funded");
        assertThat(body).doesNotContain("sk-or-unfunded").doesNotContain("sk-or-revoked");
        assertThat(body).doesNotContain("v1:"); // no ciphertext leaks either
        assertThat(body.split("upstreamCredentials", -1)).hasSize(2);
    }

    @Test
    void creditPendingMarksOnlyTheGrantedBudgetStillWaitingForItsKey() throws Exception {
        // Omitting the credential says "no commercial axis" and nothing more,
        // but the gateway has to answer two different sentences: apply for a
        // budget, or wait for the one you were granted. Only the second is a
        // wait, so only the second is flagged.
        long account = insertOpenrouterAccount();
        KeyFixture waiting = newKey("waiting");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ? where id = ?", account, waiting.id());
        KeyFixture unfunded = newKey("no-budget");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 0 where id = ?",
                unfunded.id());
        KeyFixture provisioned = newKey("already-provisioned");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, "
                + "openrouter_key_hash = 'hash-done', openrouter_key_enc = ? where id = ?",
                account, credentialCipher.encrypt("sk-or-done"), provisioned.id());
        // Funded and unprovisioned, but revoked: the sweep will never pick it
        // up, so promising a wait would be a promise nothing keeps.
        KeyFixture revoked = newKey("revoked-waiting");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, "
                + "status = 'REVOKED', revoked_at = now() where id = ?", account, revoked.id());
        // The same trap wearing an ACTIVE status: expiry is a timestamp, not a
        // column flip, so this row still reads ACTIVE and still reaches the
        // document. The sweep skips it, so the flag must too.
        KeyFixture expired = newKey("expired-waiting");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_account_id = ?, "
                + "expires_at = now() - interval '1 day' where id = ?", account, expired.id());

        syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(pending(waiting).value(true))
                .andExpect(pending(unfunded).value(false))
                .andExpect(pending(provisioned).value(false))
                .andExpect(pending(revoked).value(false))
                .andExpect(pending(expired).value(false));
    }

    /** The {@code creditPending} member of one key in the served document. */
    private static JsonPathResultMatchers pending(KeyFixture key) {
        return jsonPath("$.keys[?(@.keyId=='%s')].creditPending".formatted(key.publicId()));
    }

    @Test
    void reportedGenerationAboveOursRaisesOursAndServesTheDocument() throws Exception {
        syncFrom(SOURCE, TOKEN, poll(0)).andExpect(status().isOk()); // creates gen 1
        // A restored backup: the gateway's persisted high-water (42) is above
        // our counter. Discarding (the relay link's answer) would wedge the
        // link forever — instead ours is raised above it and the full
        // document served.
        syncFrom(SOURCE, TOKEN, poll(42))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(43))
                .andExpect(jsonPath("$.keys").isArray());
        Long stored = jdbcTemplate.queryForObject(
                "select generation from llm_gateway_state", Long.class);
        assertThat(stored).isEqualTo(43);
        assertThat(raiseAudits()).isEqualTo(1);
        // The raise is once, not per poll: the follow-up is an ordinary
        // unchanged answer and no second audit lands.
        String body = syncFrom(SOURCE, TOKEN, poll(43))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"generation\":43}");
        assertThat(raiseAudits()).isEqualTo(1);
    }

    @Test
    void pendingKeysAreAbsentFromTheDocumentEntirely() throws Exception {
        // A key between approval and mint has no secret: it authenticates
        // nothing, so it is ABSENT from the document, not present-and-refused
        // (a null tokenHash would be an entry the gateway rejects). The issued
        // key beside it is served normally.
        KeyFixture pending = newPendingKey("pending");
        KeyFixture issued = newKey("issued");
        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].keyId").value(issued.publicId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(pending.publicId().toString());
    }

    @Test
    void revokedKeysStayThroughTheGracePeriodThenDrop() throws Exception {
        KeyFixture fresh = newKey("revoked-fresh");
        KeyFixture stale = newKey("revoked-stale");
        jdbcTemplate.update("""
                update llm_api_keys set status = 'REVOKED', revoked_at = now() where id = ?
                """, fresh.id());
        jdbcTemplate.update("""
                update llm_api_keys set status = 'REVOKED',
                       revoked_at = now() - interval '40 days' where id = ?
                """, stale.id());
        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // In grace: present WITH its status, so the gateway can answer
        // "api_key_revoked" instead of "invalid_api_key".
        assertThat(body).contains(fresh.publicId().toString()).contains("REVOKED");
        // Past grace: dropped — "invalid_api_key" is the correct answer now.
        assertThat(body).doesNotContain(stale.publicId().toString());
    }

    @Test
    void expiredKeysServeActiveWithTheirExpiryThenDrop() throws Exception {
        KeyFixture recent = newKey("expired-recent");
        KeyFixture stale = newKey("expired-stale");
        jdbcTemplate.update("""
                update llm_api_keys set status = 'EXPIRED',
                       expires_at = now() - interval '1 hour' where id = ?
                """, recent.id());
        jdbcTemplate.update("""
                update llm_api_keys set status = 'EXPIRED',
                       expires_at = now() - interval '40 days' where id = ?
                """, stale.id());
        String body = syncFrom(SOURCE, TOKEN, poll(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.keys[0].expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        // EXPIRED is not in the gateway's vocabulary (an unknown status drops
        // the entry, an outage for its owner): the gateway enforces expiresAt
        // itself, so the row is served ACTIVE with its real expiry.
        assertThat(body).contains(recent.publicId().toString());
        assertThat(body).doesNotContain(stale.publicId().toString());
    }

    // ── usage ingest ────────────────────────────────────────────────────────

    @Test
    void duplicateEventsAreCountedAndInsertedOnce() throws Exception {
        KeyFixture key = newKey("usage");
        Map<String, Object> event = event("evt-1", key.publicId().toString(),
                "2026-08-10T20:03:57.974941509Z");
        usage(Map.of("events", List.of(event)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(0));
        // At-least-once redelivery: the re-sent event is a duplicate, the new
        // one accepted, and the id lands exactly once.
        usage(Map.of("events", List.of(event,
                event("evt-2", key.publicId().toString(), "2026-08-10T20:04:01Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_events where event_id = 'evt-1'", Long.class);
        assertThat(rows).isEqualTo(1);
        // last_used_at moved to the newest accepted requestedAt for the key.
        String lastUsed = jdbcTemplate.queryForObject("""
                select to_char(last_used_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
                  from llm_api_keys where id = ?
                """, String.class, key.id());
        assertThat(lastUsed).isEqualTo("2026-08-10T20:04:01");
    }

    @Test
    void aBatchWithBadEventsStillAnswers2xxAndKeepsTheRest() throws Exception {
        // One event per defect class — missing id, missing status, unparseable
        // timestamp — plus a good one. A 4xx here would make the gateway skip
        // the batch and move its checkpoint past it: the good event would be
        // gone for good.
        Map<String, Object> noId = new HashMap<>(
                event(null, null, "2026-08-10T20:03:57Z"));
        noId.remove("eventUuid");
        Map<String, Object> noStatus = new HashMap<>(
                event("evt-bad-status", null, "2026-08-10T20:03:57Z"));
        noStatus.remove("status");
        Map<String, Object> badTime = event("evt-bad-time", null, "not-a-timestamp");
        Map<String, Object> good = event("evt-good", null, "2026-08-10T20:03:57Z");
        usage(Map.of("events", List.of(noId, noStatus, badTime, good)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(3));
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_events", Long.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void unattributedEventsAreKeptWithANullKey() throws Exception {
        // No keyId at all, and a keyId that resolves to nothing: both are
        // kept — they are the only trace of a client looping on a bad key.
        usage(Map.of("events", List.of(
                event("evt-nokey", null, "2026-08-10T20:03:57Z"),
                event("evt-unknownkey", SeedFixtures.UNKNOWN_ID.toString(),
                        "2026-08-10T20:03:58Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));
        Long nullKeyRows = jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_events where key_id is null", Long.class);
        assertThat(nullKeyRows).isEqualTo(2);
    }

    @Test
    void eventFieldsSurviveVerbatimIncludingTheUpstreamSplit() throws Exception {
        // upstreamRef/attempts are the only record of which upstream actually
        // served a request behind the shared public name.
        Map<String, Object> event = event("evt-full", null, "2026-08-10T20:03:57Z");
        event.put("upstreamRef", "backup");
        event.put("attempts", 2);
        event.put("generation", 43);
        event.put("publicModelName", "pnu-general");
        event.put("budgetAxis", "CREDIT");
        event.put("inputTokens", 18);
        event.put("outputTokens", 694);
        event.put("estimated", false);
        event.put("ttftMs", 6419);
        usage(Map.of("events", List.of(event))).andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select upstream_ref, attempts, generation, public_model_name, budget_axis,
                       input_tokens, output_tokens, ttft_ms
                  from llm_usage_events where event_id = 'evt-full'
                """);
        assertThat(row.get("upstream_ref")).isEqualTo("backup");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("generation")).longValue()).isEqualTo(43);
        assertThat(row.get("public_model_name")).isEqualTo("pnu-general");
        assertThat(row.get("budget_axis")).isEqualTo("CREDIT");
        assertThat(((Number) row.get("input_tokens")).intValue()).isEqualTo(18);
        assertThat(((Number) row.get("output_tokens")).intValue()).isEqualTo(694);
        assertThat(((Number) row.get("ttft_ms")).longValue()).isEqualTo(6419);
    }

    @Test
    void missingOrInvalidBudgetAxisIsAcceptedAsUnknown() throws Exception {
        Map<String, Object> missing = event("evt-axis-missing", null,
                "2026-08-10T20:03:57Z");
        Map<String, Object> invalid = event("evt-axis-invalid", null,
                "2026-08-10T20:03:58Z");
        invalid.put("budgetAxis", "SOMETHING_NEW");

        usage(Map.of("events", List.of(missing, invalid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0));
        Long unknown = jdbcTemplate.queryForObject("""
                select count(*) from llm_usage_events
                 where event_id in ('evt-axis-missing', 'evt-axis-invalid')
                   and budget_axis is null
                """, Long.class);
        assertThat(unknown).isEqualTo(2);
    }

    // ── bodies channel ──────────────────────────────────────────────────────

    @Test
    void capturedTextNeverLandsInTheUsageTable() throws Exception {
        // The two channels stay separate at the destination as well as on the
        // wire. This record names no key, so nothing stores it either way --
        // what is pinned here is that it does not become a usage event.
        // Storage itself lives in LlmBodyIngestTest.
        bodies(Map.of("records", List.of(Map.of(
                        "eventUuid", "evt-b1",
                        "requestedAt", "2026-08-10T20:03:57Z",
                        "request", List.of(Map.of("role", "user", "content", "hello")),
                        "response", "hi",
                        "requestTruncated", false,
                        "responseTruncated", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(1));
        Long usageRows = jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_events", Long.class);
        assertThat(usageRows).isZero();
    }

    // ── self-report persistence ─────────────────────────────────────────────

    @Test
    void selfReportFieldsAreSanitizedAndStored() throws Exception {
        Map<String, Object> body = poll(0);
        body.put("agentVersion", "7eb7a60\u001b[31mRED\u001b[0m");
        body.put("startedAt", "2026-08-11T04:10:22Z");
        body.put("maxInFlight", 16);
        body.put("upstreamRefs", List.of("main", "backup", "a".repeat(129)));
        body.put("rejectedEntries", 3);
        body.put("lastError", "boom\r\nline");
        body.put("bodiesDropped", 7);
        body.put("usageShipFailures", 1);
        body.put("spoolWriteFailures", 2);
        syncFrom(SOURCE, TOKEN, body).andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select agent_version, max_in_flight, upstream_refs, rejected_entries,
                       last_error, bodies_dropped, usage_ship_failures, spool_write_failures
                  from llm_gateway_state
                """);
        assertThat(row.get("agent_version")).isEqualTo("7eb7a60[31mRED[0m"); // ESC gone
        assertThat(((Number) row.get("max_in_flight")).intValue()).isEqualTo(16);
        assertThat((String) row.get("upstream_refs")).contains("main").contains("backup");
        assertThat((String) row.get("upstream_refs")).doesNotContain("a".repeat(20));
        assertThat(((Number) row.get("rejected_entries")).intValue()).isEqualTo(3);
        assertThat(row.get("last_error")).isEqualTo("boomline"); // CR/LF gone
        assertThat(((Number) row.get("bodies_dropped")).longValue()).isEqualTo(7);
        assertThat(((Number) row.get("usage_ship_failures")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("spool_write_failures")).longValue()).isEqualTo(2);
    }

    @Test
    void versionedUpstreamObservationsAndUsageQueueAreStoredAsCurrentState() throws Exception {
        Map<String, Object> body = poll(0);
        body.put("upstreamObservationFormat", 1);
        body.put("lastUsageShipSuccessAt", "2026-08-30T02:00:00Z");
        body.put("oldestUnshippedEventAt", "2026-08-30T02:01:00Z");
        body.put("usageQueueObservedAt", "2026-08-30T02:01:30Z");
        body.put("upstreams", List.of(Map.of(
                "ref", "DGX",
                "passive", Map.of(
                        "lastAttemptAt", "2026-08-30T02:02:00Z",
                        "lastSuccessAt", "2026-08-30T02:02:00Z",
                        "consecutiveFailures", 0),
                "active", Map.of(
                        "lastAttemptAt", "2026-08-30T02:03:00Z",
                        "lastSuccessAt", "2026-08-30T02:03:00Z",
                        "lastFailureType", "TRANSPORT_ERROR",
                        "status", "OK", "intervalSeconds", 60,
                        "latencyMs", 24,
                        "consecutiveFailures", 0),
                "catalog", Map.of(
                        "status", "MISMATCH", "expectedModelCount", 3,
                        "missingModelCount", 1, "unexpectedModelCount", 2,
                        "missingPublicModels", List.of("pickle-coder")))));

        syncFrom(SOURCE, TOKEN, body).andExpect(status().isOk());

        Map<String, Object> gateway = jdbcTemplate.queryForMap("""
                select upstream_observation_format, queued_usage_events, queued_usage_bytes,
                       last_usage_ship_success_at, oldest_unshipped_event_at,
                       usage_queue_observed_at, usage_queue_scan_failures, rejected_entries,
                       reload_failures, bodies_dropped, usage_ship_failures,
                       spool_write_failures
                  from llm_gateway_state
                """);
        assertThat(((Number) gateway.get("upstream_observation_format")).intValue()).isEqualTo(1);
        assertThat(((Number) gateway.get("queued_usage_events")).longValue()).isZero();
        assertThat(((Number) gateway.get("queued_usage_bytes")).longValue()).isZero();
        assertThat(gateway.get("last_usage_ship_success_at")).isNotNull();
        assertThat(gateway.get("oldest_unshipped_event_at")).isNotNull();
        assertThat(gateway.get("usage_queue_observed_at")).isNotNull();
        assertThat(((Number) gateway.get("usage_queue_scan_failures")).longValue()).isZero();
        assertThat(((Number) gateway.get("rejected_entries")).intValue()).isZero();
        assertThat(((Number) gateway.get("reload_failures")).longValue()).isZero();
        assertThat(((Number) gateway.get("bodies_dropped")).longValue()).isZero();
        assertThat(((Number) gateway.get("usage_ship_failures")).longValue()).isZero();
        assertThat(((Number) gateway.get("spool_write_failures")).longValue()).isZero();

        Map<String, Object> upstream = jdbcTemplate.queryForMap("""
                select ref, configured, active_status, active_failure_type,
                       active_probe_interval_seconds, active_latency_ms, active_model_count,
                       catalog_status,
                       catalog_unexpected_model_count, catalog_missing_public_models
                  from llm_upstream_state where ref = 'dgx'
                """);
        assertThat(upstream.get("ref")).isEqualTo("dgx");
        assertThat(upstream.get("configured")).isEqualTo(true);
        assertThat(upstream.get("active_status")).isEqualTo("OK");
        assertThat(upstream.get("active_failure_type")).isEqualTo("TRANSPORT_ERROR");
        assertThat(((Number) upstream.get("active_probe_interval_seconds")).intValue())
                .isEqualTo(60);
        assertThat(((Number) upstream.get("active_model_count")).intValue()).isZero();
        assertThat(((Number) upstream.get("active_latency_ms")).longValue()).isEqualTo(24);
        assertThat(upstream.get("catalog_status")).isEqualTo("MISMATCH");
        assertThat(((Number) upstream.get("catalog_unexpected_model_count")).intValue())
                .isEqualTo(2);
        assertThat(upstream.get("catalog_missing_public_models").toString())
                .contains("pickle-coder");
    }

    @Test
    void anUnprobedUpstreamReportsNoModelCountWithoutFailingTheWholeSync() throws Exception {
        // A probe that has failed, or has not run yet, reports a status other
        // than OK and no model count. That pair used to throw on the way in
        // and answer 500, which cost the gateway every document behind it:
        // revocations, limit changes and quota flips all stop propagating
        // while sync is failing, and the gateway keeps serving the last one
        // it got, so nothing looks broken from outside.
        Map<String, Object> body = poll(0);
        body.put("upstreamObservationFormat", 1);
        body.put("upstreams", List.of(Map.of(
                "ref", "dgx",
                "active", Map.of("status", "FAILED", "consecutiveFailures", 3))));

        syncFrom(SOURCE, TOKEN, body).andExpect(status().isOk());

        Map<String, Object> upstream = jdbcTemplate.queryForMap("""
                select active_status, active_model_count
                  from llm_upstream_state where ref = 'dgx'
                """);
        assertThat(upstream.get("active_status")).isEqualTo("FAILED");
        assertThat(upstream.get("active_model_count"))
                .as("unknown stays unknown; only a healthy probe means zero models")
                .isNull();
    }

    @Test
    void oldGatewayOmissionPreservesRowsButVersionedEmptyListDeconfiguresThem() throws Exception {
        Map<String, Object> versioned = poll(0);
        versioned.put("upstreamObservationFormat", 1);
        versioned.put("upstreams", List.of(Map.of("ref", "dgx"),
                Map.of("ref", "openrouter")));
        syncFrom(SOURCE, TOKEN, versioned).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select configured from llm_upstream_state where ref = 'dgx'", Boolean.class))
                .isTrue();

        // A rolled-back binary has no format member. It updates the heartbeat,
        // but its omission is not an authoritative empty configured set.
        syncFrom(SOURCE, TOKEN, poll(1)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select configured from llm_upstream_state where ref = 'dgx'", Boolean.class))
                .isTrue();

        Map<String, Object> malformed = poll(1);
        malformed.put("upstreamObservationFormat", 1);
        malformed.put("upstreams", java.util.Arrays.asList(
                Map.of("ref", "dgx", "active", Map.of("status", "OK")), null,
                Map.of("ref", "not a valid ref")));
        syncFrom(SOURCE, TOKEN, malformed).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select active_status from llm_upstream_state where ref = 'dgx'", String.class))
                .isEqualTo("OK");
        assertThat(jdbcTemplate.queryForObject("select configured from llm_upstream_state "
                + "where ref = 'openrouter'", Boolean.class)).isTrue();

        String validPrefix = "a".repeat(128);
        Map<String, Object> overlongIdentity = poll(1);
        overlongIdentity.put("upstreamObservationFormat", 1);
        overlongIdentity.put("upstreams", List.of(Map.of("ref", validPrefix + "z")));
        syncFrom(SOURCE, TOKEN, overlongIdentity).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_upstream_state where ref = ?", Long.class,
                validPrefix)).isZero();
        assertThat(jdbcTemplate.queryForObject("select configured from llm_upstream_state "
                + "where ref = 'openrouter'", Boolean.class)).isTrue();

        Map<String, Object> duplicate = poll(1);
        duplicate.put("upstreamObservationFormat", 1);
        duplicate.put("upstreams", List.of(Map.of("ref", "dgx"), Map.of("ref", "DGX")));
        syncFrom(SOURCE, TOKEN, duplicate).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select configured from llm_upstream_state "
                + "where ref = 'openrouter'", Boolean.class)).isTrue();

        List<Map<String, Object>> overflowRows = new java.util.ArrayList<>();
        for (int i = 0; i <= LlmSyncService.MAX_UPSTREAM_REFS; i++) {
            overflowRows.add(Map.of("ref", "overflow-" + i));
        }
        Map<String, Object> overflow = poll(1);
        overflow.put("upstreamObservationFormat", 1);
        overflow.put("upstreams", overflowRows);
        syncFrom(SOURCE, TOKEN, overflow).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select configured from llm_upstream_state "
                + "where ref = 'openrouter'", Boolean.class)).isTrue();

        // A newer observation shape is also unknown to this build. Treating
        // its list as v1 would make an API rollback silently deconfigure rows.
        Map<String, Object> future = poll(1);
        future.put("upstreamObservationFormat", 2);
        future.put("upstreams", List.of());
        syncFrom(SOURCE, TOKEN, future).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select configured from llm_upstream_state where ref = 'dgx'", Boolean.class))
                .isTrue();

        Map<String, Object> empty = poll(1);
        empty.put("upstreamObservationFormat", 1);
        empty.put("upstreams", List.of());
        syncFrom(SOURCE, TOKEN, empty).andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select configured, deconfigured_at from llm_upstream_state where ref = 'dgx'");
        assertThat(row.get("configured")).isEqualTo(false);
        assertThat(row.get("deconfigured_at")).isNotNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A minimal poll body: the three always-present members. */
    private static Map<String, Object> poll(long appliedGeneration) {
        Map<String, Object> body = new HashMap<>();
        body.put("appliedGeneration", appliedGeneration);
        body.put("supportedFormat", 1);
        body.put("inFlight", 0);
        return body;
    }

    /** A minimal valid usage event (the always-present members only). */
    private static Map<String, Object> event(String eventUuid, String keyId,
            String requestedAt) {
        Map<String, Object> event = new HashMap<>();
        if (eventUuid != null) {
            event.put("eventUuid", eventUuid);
        }
        if (keyId != null) {
            event.put("keyId", keyId);
        }
        event.put("status", "OK");
        event.put("inputTokens", 1);
        event.put("outputTokens", 1);
        event.put("latencyMs", 10);
        event.put("requestedAt", requestedAt);
        return event;
    }

    private ResultActions syncFrom(String source, String token, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/internal/llm/sync")
                .with(remoteAddr(source))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions usage(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/internal/llm/usage")
                .with(remoteAddr(SOURCE))
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions bodies(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/internal/llm/bodies")
                .with(remoteAddr(SOURCE))
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long stateRowCount() {
        return jdbcTemplate.queryForObject("select count(*) from llm_gateway_state", Long.class);
    }

    private long raiseAudits() {
        return jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'llm_gateway.generation_raise'
                """, Long.class);
    }

    private record KeyFixture(long id, UUID publicId, String tokenHash) {
    }

    /** An issued (ACTIVE, minted-secret) key in its own workspace. */
    private KeyFixture newKey(String slug) {
        String plaintext = LlmApiKeyTokens.newToken();
        String tokenHash = LlmApiKeyTokens.hash(plaintext);
        return insertKey(slug, tokenHash, LlmApiKeyTokens.visiblePrefix(plaintext), "ACTIVE");
    }

    /** A key the approval created but the owner has not minted yet. */
    private KeyFixture newPendingKey(String slug) {
        return insertKey(slug, null, null, "PENDING");
    }

    private long insertOpenrouterAccount() {
        return jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                returning id
                """, Long.class, SeedFixtures.seedOrgId(jdbcTemplate),
                "게이트웨이 시험 사업 " + UUID.randomUUID(),
                SeedFixtures.orgadminId(jdbcTemplate));
    }

    private KeyFixture insertKey(String slug, String tokenHash, String tokenPrefix,
            String status) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "LLM 키 테스트 " + slug + "-" + unique);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, ownerId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "LLM 키 테스트", "llm-" + slug);
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name,
                                          token_hash, token_prefix, status, created_by)
                values (?, ?, ?, ?, ?, ?, ?::llm_api_key_status, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + slug,
                tokenHash, tokenPrefix, status, ownerId);
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, id);
        return new KeyFixture(id, publicId, tokenHash);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(
            String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
