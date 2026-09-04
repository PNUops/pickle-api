package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
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
 * Body-record ingest: what is stored, what is deliberately dropped, and the two
 * properties invisible from outside -- nothing is written in the clear, and
 * this path touches no row outside its own table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmBodyIngestTest {

    private static final String SOURCE = "172.30.1.40";
    private static final String TOKEN = "test-llm-gateway-token";
    private static final String PROMPT_TEXT = "\ud559\ubc88\uc774 202012345\uc778\ub370 \uc131\uc801 \uc870\ud68c \ucf54\ub4dc \uc880";
    private static final String ANSWER_TEXT = "\ub124, \uc774\ub807\uac8c \ud558\uc138\uc694";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-gateway.token", () -> TOKEN);
        registry.add("pickle.llm-body-keyring.write-key-id", () -> "v1");
        registry.add("pickle.llm-body-keyring.read-keys", () -> "v1=" + Base64.getEncoder()
                .encodeToString(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from llm_gateway_state");
    }

    @Test
    void storesACapturedExchangeAndNeverInTheClear() throws Exception {
        UUID key = recordingKey();

        bodies(batch(record(key, "evt-1", messages(PROMPT_TEXT), ANSWER_TEXT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.rejected").value(0));

        Map<String, Object> row = jdbcTemplate.queryForMap("select * from llm_request_bodies");
        // The point of the whole encryption axis: the prompt is not in the
        // column, and what is there says which keyring entry opens it.
        assertThat((String) row.get("request_enc"))
                .startsWith("llmb-v1:v1:")
                .doesNotContain(PROMPT_TEXT);
        assertThat((String) row.get("response_enc"))
                .startsWith("llmb-v1:v1:")
                .doesNotContain(ANSWER_TEXT);
        assertThat(row.get("cipher_key_id")).isEqualTo("v1");
        assertThat(row.get("event_id")).isEqualTo("evt-1");
        assertThat(row.get("request_truncated")).isEqualTo(false);
        // Plaintext byte length, not the ciphertext's.
        assertThat((Integer) row.get("response_bytes"))
                .isEqualTo(ANSWER_TEXT.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aTimestampDerivedEventIdIsStoredRatherThanRejected() throws Exception {
        // The gateway falls back to "t-<nanos>" when its random source fails. A
        // uuid column would reject that row, and this channel never re-sends.
        UUID key = recordingKey();

        bodies(batch(record(key, "t-1755500000000000000", messages("hi"), "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "select event_id from llm_request_bodies", String.class))
                .isEqualTo("t-1755500000000000000");
    }

    @Test
    void aRecordWithNoResolvableKeyIsSkippedRatherThanStored() throws Exception {
        // Unlike a usage event, a body with no key is unreadable forever: every
        // read path is scoped to a key's access list.
        recordingKey();

        bodies(batch(
                record(UUID.randomUUID(), "evt-unknown", messages("hi"), "hello"),
                recordWithRawKey("not-a-uuid", "evt-garbage", messages("hi"), "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.accepted").value(0));

        assertThat(bodyCount()).isZero();
    }

    @Test
    void inFlightRecordsForAKeyThatJustTurnedRecordingOffAreSkipped() throws Exception {
        // The gateway polls, so records captured moments before the toggle went
        // off are genuinely in flight. This row is the authority, not the
        // snapshot the gateway was holding.
        UUID key = recordingKey();
        jdbcTemplate.update("update llm_api_keys set record_bodies = false");

        bodies(batch(record(key, "evt-1", messages(PROMPT_TEXT), "answer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(1));

        assertThat(bodyCount()).isZero();
    }

    @Test
    void aMalformedRecordCostsItselfAndNotTheBatch() throws Exception {
        // A non-2xx would make the gateway drop the whole batch, and it never
        // re-sends -- so the good record here must survive its neighbours.
        UUID key = recordingKey();
        Map<String, Object> tooLong = record(key, "e".repeat(65), messages("hi"), "a");
        Map<String, Object> badTime = record(key, "evt-bad-time", messages("hi"), "a");
        badTime.put("requestedAt", "not-a-timestamp");
        Map<String, Object> controlChar = record(key, "evt\u0001id", messages("hi"), "a");
        Map<String, Object> blankId = record(key, "  ", messages("hi"), "a");

        bodies(batch(record(key, "evt-good", messages(PROMPT_TEXT), "answer"),
                tooLong, badTime, controlChar, blankId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(5));

        assertThat(jdbcTemplate.queryForObject(
                "select event_id from llm_request_bodies", String.class)).isEqualTo("evt-good");
    }

    @Test
    void aRecordThatFailsInsideTheDatabaseDoesNotTakeTheBatchWithIt() throws Exception {
        // Postgres aborts a transaction on a statement error, so catching the
        // exception per record and carrying on leaves every later statement
        // failing too -- and the COMMIT then rolls back silently, with the
        // reply still saying accepted. The gateway discards a batch it was
        // told about, so that combination destroys text and reports success.
        UUID key = recordingKey();
        Map<String, Object> poison = record(key, "evt-poison", messages("hi"), "a");
        // Parses as an instant, does not fit timestamptz.
        poison.put("requestedAt", "+300000-01-01T00:00:00Z");

        bodies(batch(record(key, "evt-first", messages(PROMPT_TEXT), "answer"),
                poison,
                record(key, "evt-third", messages("third"), "answer")))
                .andExpect(status().isOk());

        // Whatever the tally says, it must match what is actually stored.
        assertThat(eventIds()).containsExactlyInAnyOrder("evt-first", "evt-third");
    }

    @Test
    void aRepeatedEventIdIsADuplicateAndLeavesTheStoredBytesAlone() throws Exception {
        UUID key = recordingKey();
        bodies(batch(record(key, "evt-1", messages(PROMPT_TEXT), "answer")))
                .andExpect(status().isOk());
        String first = jdbcTemplate.queryForObject(
                "select request_enc from llm_request_bodies", String.class);

        bodies(batch(record(key, "evt-1", messages(PROMPT_TEXT), "answer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.accepted").value(0));

        assertThat(bodyCount()).isEqualTo(1);
        // Byte-identical: "do nothing", not "do update". Re-encrypting would
        // churn the row under a fresh IV for no gain.
        assertThat(jdbcTemplate.queryForObject(
                "select request_enc from llm_request_bodies", String.class)).isEqualTo(first);
    }

    @Test
    void aTruncatedPromptArrivesAsAStringAndKeepsItsFlag() throws Exception {
        UUID key = recordingKey();
        Map<String, Object> whole = record(key, "evt-whole", messages("short"), "a");
        Map<String, Object> cut = record(key, "evt-cut",
                "[{\"role\":\"user\",\"content\":\"cut off here", "b");
        cut.put("requestTruncated", true);

        bodies(batch(whole, cut)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));

        assertThat(truncatedFlag("evt-cut")).isTrue();
        assertThat(truncatedFlag("evt-whole")).isFalse();
    }

    @Test
    void aRecordCarryingNoTextAtAllIsSkipped() throws Exception {
        // Metadata with neither side duplicates the usage event and nothing more.
        UUID key = recordingKey();

        bodies(batch(record(key, "evt-empty", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(1));

        assertThat(bodyCount()).isZero();
    }

    @Test
    void ingestWritesNothingOutsideItsOwnTable() throws Exception {
        // The lock-order property, pinned. Stamping last_used_at or bumping the
        // generation here would make this the one writer that reaches a key row
        // without taking the counter first.
        UUID key = recordingKey();
        jdbcTemplate.update("update llm_api_keys set last_used_at = null");
        Long generationBefore = generation();

        bodies(batch(record(key, "evt-1", messages(PROMPT_TEXT), "answer")))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_api_keys where last_used_at is not null", Long.class))
                .isZero();
        assertThat(generation()).isEqualTo(generationBefore);
        // And captured text never leaks into the usage table.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_events", Long.class)).isZero();
    }

    // -- helpers ------------------------------------------------------------

    private java.util.List<String> eventIds() {
        return jdbcTemplate.queryForList(
                "select event_id from llm_request_bodies order by event_id", String.class);
    }

    private long bodyCount() {
        return jdbcTemplate.queryForObject("select count(*) from llm_request_bodies", Long.class);
    }

    private Long generation() {
        return jdbcTemplate.queryForObject(
                "select coalesce(max(applied_generation), 0) from llm_gateway_state", Long.class);
    }

    private Boolean truncatedFlag(String eventId) {
        return jdbcTemplate.queryForObject(
                "select request_truncated from llm_request_bodies where event_id = ?",
                Boolean.class, eventId);
    }

    /** An ACTIVE key in its own workspace with recording switched on. */
    private UUID recordingKey() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "llm-body-test " + unique);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, ownerId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "llm-body-test", "llm-" + unique);
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, record_bodies, created_by)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE'::llm_api_key_status, true, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                "hash-" + unique, "pickle-" + unique.substring(0, 2), ownerId);
        return jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, id);
    }

    private static List<Map<String, Object>> messages(String text) {
        return List.of(Map.of("role", "user", "content", text));
    }

    private static Map<String, Object> record(UUID keyId, String eventId, Object request,
            String response) {
        return recordWithRawKey(keyId.toString(), eventId, request, response);
    }

    private static Map<String, Object> recordWithRawKey(String keyId, String eventId,
            Object request, String response) {
        Map<String, Object> record = new HashMap<>();
        record.put("eventUuid", eventId);
        record.put("keyId", keyId);
        record.put("requestedAt", "2026-09-04T11:03:57Z");
        record.put("request", request);
        record.put("response", response);
        record.put("requestTruncated", false);
        record.put("responseTruncated", false);
        return record;
    }

    @SafeVarargs
    private static Map<String, Object> batch(Map<String, Object>... records) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentVersion", "test");
        body.put("records", Arrays.asList(records));
        return body;
    }

    private ResultActions bodies(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/internal/llm/bodies")
                .with(request -> {
                    request.setRemoteAddr(SOURCE);
                    return request;
                })
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
