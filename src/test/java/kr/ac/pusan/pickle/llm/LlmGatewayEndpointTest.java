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
import tools.jackson.databind.ObjectMapper;

/**
 * LLM gateway surface: the dedicated chain (static bearer with rotation
 * overlap, source pin, per-sub-path buckets and caps) ordered ahead of the
 * fail-closed {@code /internal/**} catch-all; sync semantics (unchanged
 * answers are the bare generation with NO document members; a changed answer
 * carries {@code models} and {@code keys} together; a reported generation
 * above ours raises ours instead of being discarded); usage ingest (per-event
 * rejection never a 4xx, event-id dedup, unattributed events kept); and the
 * bodies channel's deliberate accept-and-discard.
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
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from llm_models");
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
        bodies(Map.of("records", List.of())).andExpect(status().isNoContent());
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

    @Test
    void upstreamCredentialTravelsOnlyForActiveFundedProvisionedKeys() throws Exception {
        // The one usable secret in the document: present exactly when the key
        // is ACTIVE, its money budget positive, and its OpenRouter key
        // provisioned. Every other state omits the member, and omission is
        // what closes the commercial axis for the key.
        KeyFixture funded = newKey("funded");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_key_enc = ? where id = ?",
                credentialCipher.encrypt("sk-or-funded"), funded.id());
        KeyFixture unfunded = newKey("unfunded");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 0, "
                + "openrouter_key_enc = ? where id = ?",
                credentialCipher.encrypt("sk-or-unfunded"), unfunded.id());
        KeyFixture unprovisioned = newKey("unprovisioned");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00 where id = ?",
                unprovisioned.id());
        KeyFixture revoked = newKey("revoked-funded");
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00, "
                + "openrouter_key_enc = ?, status = 'REVOKED', revoked_at = now() "
                + "where id = ?", credentialCipher.encrypt("sk-or-revoked"), revoked.id());

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
        event.put("inputTokens", 18);
        event.put("outputTokens", 694);
        event.put("estimated", false);
        event.put("ttftMs", 6419);
        usage(Map.of("events", List.of(event))).andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select upstream_ref, attempts, generation, public_model_name,
                       input_tokens, output_tokens, ttft_ms
                  from llm_usage_events where event_id = 'evt-full'
                """);
        assertThat(row.get("upstream_ref")).isEqualTo("backup");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("generation")).longValue()).isEqualTo(43);
        assertThat(row.get("public_model_name")).isEqualTo("pnu-general");
        assertThat(((Number) row.get("input_tokens")).intValue()).isEqualTo(18);
        assertThat(((Number) row.get("output_tokens")).intValue()).isEqualTo(694);
        assertThat(((Number) row.get("ttft_ms")).longValue()).isEqualTo(6419);
    }

    // ── bodies channel ──────────────────────────────────────────────────────

    @Test
    void bodiesAreAcceptedAndDeliberatelyNotStored() throws Exception {
        // 2xx keeps the gateway's bounded queue draining; storage is a later
        // round gated on the privacy-policy decision, so acceptance leaves no
        // row anywhere.
        bodies(Map.of("records", List.of(Map.of(
                        "eventUuid", "evt-b1",
                        "requestedAt", "2026-08-10T20:03:57Z",
                        "request", List.of(Map.of("role", "user", "content", "hello")),
                        "response", "hi",
                        "requestTruncated", false,
                        "responseTruncated", false))))
                .andExpect(status().isNoContent());
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
        body.put("upstreamRefs", List.of("main", "backup"));
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
        assertThat(((Number) row.get("rejected_entries")).intValue()).isEqualTo(3);
        assertThat(row.get("last_error")).isEqualTo("boomline"); // CR/LF gone
        assertThat(((Number) row.get("bodies_dropped")).longValue()).isEqualTo(7);
        assertThat(((Number) row.get("usage_ship_failures")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("spool_write_failures")).longValue()).isEqualTo(2);
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
