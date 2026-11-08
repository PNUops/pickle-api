package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Async-object ownership ledger on the LLM gateway link: the two-phase
 * submit record (intent, then bind), the two-valued lookup that cannot tell
 * another key's object from no object, release, and the refusals the gateway
 * acts on by code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmObjectOwnershipTest {

    private static final String SOURCE = "172.30.1.40";
    private static final String TOKEN = "test-llm-gateway-token";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-gateway.token", () -> TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LlmAsyncObjectSweeper sweeper;

    private final List<Long> keyIds = new ArrayList<>();
    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> workspaceIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();

    @BeforeEach
    void clearLedger() {
        jdbcTemplate.update("delete from llm_async_objects");
    }

    /**
     * Other suites delete every key and account row, and this table's foreign
     * keys would block them, so nothing this suite creates outlives it.
     */
    @AfterEach
    void removeFixtures() {
        jdbcTemplate.update("delete from llm_async_objects");
        for (Long id : keyIds) {
            jdbcTemplate.update("delete from llm_usage_events where key_id = ?", id);
            jdbcTemplate.update("delete from llm_api_keys where id = ?", id);
        }
        for (Long id : accountIds) {
            jdbcTemplate.update("delete from openrouter_accounts where id = ?", id);
        }
        for (Long id : requestIds) {
            jdbcTemplate.update("delete from requests where id = ?", id);
        }
        for (Long id : workspaceIds) {
            jdbcTemplate.update("delete from workspaces where id = ?", id);
        }
    }

    // ── chain ───────────────────────────────────────────────────────────────

    @Test
    void ownershipCallsNeedTheGatewayBearer() throws Exception {
        mockMvc.perform(post("/internal/llm/objects/lookup").with(remoteAddr(SOURCE))
                        .header("Authorization", "Bearer not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(lookupBody(UUID.randomUUID(), "FILE", List.of("file-a")))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/llm/objects/bind").with(remoteAddr("203.0.113.9"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownObjectSubPathIsRefused() throws Exception {
        call("/internal/llm/objects/list", Map.of()).andExpect(status().isForbidden());
    }

    // ── intent ──────────────────────────────────────────────────────────────

    @Test
    void intentWritesAPendingRowForAnActiveFundedKey() throws Exception {
        Key key = fundedKey("intent");
        UUID intentId = intent(key, "FILE");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select key_id, openrouter_account_id, kind, state, vendor_object_id, event_id"
                        + " from llm_async_objects where public_id = ?", intentId);
        assertThat(row.get("key_id")).isEqualTo(key.id());
        assertThat(row.get("openrouter_account_id")).isEqualTo(key.accountId());
        assertThat(row.get("kind")).isEqualTo("FILE");
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("vendor_object_id")).isNull();
        assertThat(row.get("event_id")).isEqualTo("evt-" + key.publicId());
    }

    @Test
    void intentRefusesAKeyThatCannotStartAnything() throws Exception {
        Key revoked = fundedKey("revoked");
        jdbcTemplate.update("update llm_api_keys set status = 'REVOKED', revoked_at = now()"
                + " where id = ?", revoked.id());
        Key expired = fundedKey("expired");
        jdbcTemplate.update("update llm_api_keys set expires_at = now() - interval '1 minute'"
                + " where id = ?", expired.id());
        Key unfunded = key("unfunded");

        intentCall(revoked.publicId(), "FILE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"));
        intentCall(expired.publicId(), "FILE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"));
        intentCall(UUID.randomUUID(), "FILE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"));
        intentCall(unfunded.publicId(), "FILE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
        assertThat(rows()).isZero();
    }

    @Test
    void intentRefusesAnUnknownKind() throws Exception {
        Key key = fundedKey("kind");
        intentCall(key.publicId(), "CONTAINER").andExpect(status().isUnprocessableContent());
        assertThat(rows()).isZero();
    }

    // ── bind ────────────────────────────────────────────────────────────────

    @Test
    void bindOwnsTheObjectAndIsIdempotentForTheSameId() throws Exception {
        Key key = fundedKey("bind");
        UUID intentId = intent(key, "BATCH");

        bindCall(key.publicId(), intentId, "BATCH", "batch-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OWNED"));
        bindCall(key.publicId(), intentId, "BATCH", "batch-1").andExpect(status().isOk());
        bindCall(key.publicId(), intentId, "BATCH", "batch-2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_BOUND_ELSEWHERE"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select state, vendor_object_id, bound_at, size_bytes from llm_async_objects"
                        + " where public_id = ?", intentId);
        assertThat(row.get("state")).isEqualTo("OWNED");
        assertThat(row.get("vendor_object_id")).isEqualTo("batch-1");
        assertThat(row.get("bound_at")).isNotNull();
        assertThat(row.get("size_bytes")).isEqualTo(42L);
    }

    @Test
    void bindRefusesAnotherKeysIntentAndAMismatchedKind() throws Exception {
        Key owner = fundedKey("owner");
        Key other = fundedKey("other");
        UUID intentId = intent(owner, "FILE");

        bindCall(other.publicId(), intentId, "FILE", "file-1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTENT_UNKNOWN"));
        bindCall(owner.publicId(), intentId, "VIDEO", "file-1").andExpect(status().isNotFound());
        bindCall(owner.publicId(), UUID.randomUUID(), "FILE", "file-1")
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherKeyCannotTakeALiveVendorIdInTheSameWorkspace() throws Exception {
        Key first = fundedKey("first");
        Key second = keyOnAccount("second", first.accountId());
        bind(first, intent(first, "FILE"), "FILE", "file-shared");
        UUID theirs = intent(second, "FILE");

        bindCall(second.publicId(), theirs, "FILE", "file-shared").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBJECT_TAKEN"));
        assertThat(lookup(second, "FILE", List.of("file-shared"))).isEmpty();
        // The refusal rolled back: the losing intent is untouched.
        Map<String, Object> losing = jdbcTemplate.queryForMap(
                "select state, vendor_object_id from llm_async_objects where public_id = ?", theirs);
        assertThat(losing.get("state")).isEqualTo("PENDING");
        assertThat(losing.get("vendor_object_id")).isNull();
        assertThat(lookup(first, "FILE", List.of("file-shared"))).containsExactly("file-shared");
    }

    @Test
    void theSameIdInAnotherWorkspaceIsADifferentObject() throws Exception {
        Key first = fundedKey("ws-a");
        Key second = fundedKey("ws-b");
        bind(first, intent(first, "FILE"), "FILE", "file-same");
        bind(second, intent(second, "FILE"), "FILE", "file-same");

        assertThat(lookup(first, "FILE", List.of("file-same"))).containsExactly("file-same");
        assertThat(lookup(second, "FILE", List.of("file-same"))).containsExactly("file-same");
    }

    @Test
    void aVendorReturningAnExistingObjectToItsOwnerIsADuplicateNotAConflict() throws Exception {
        Key key = fundedKey("dup");
        UUID first = intent(key, "FILE");
        bind(key, first, "FILE", "file-dedup");
        UUID second = intent(key, "FILE");

        bindCall(key.publicId(), second, "FILE", "file-dedup").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OWNED"));
        // A repeat of the duplicate's bind (its response lost) answers the same.
        bindCall(key.publicId(), second, "FILE", "file-dedup").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select release_reason from llm_async_objects where public_id = ?",
                String.class, second)).isEqualTo("DUPLICATE");
        assertThat(jdbcTemplate.queryForObject(
                "select state from llm_async_objects where public_id = ?",
                String.class, first)).isEqualTo("OWNED");
    }

    // ── lookup ──────────────────────────────────────────────────────────────

    @Test
    void lookupAnswersOnlyTheIdsThisKeyOwns() throws Exception {
        Key key = fundedKey("look");
        Key other = keyOnAccount("look-other", key.accountId());
        bind(key, intent(key, "VIDEO"), "VIDEO", "video-mine");
        bind(other, intent(other, "VIDEO"), "VIDEO", "video-theirs");
        intent(key, "VIDEO"); // pending, never bound
        UUID released = intent(key, "VIDEO");
        bind(key, released, "VIDEO", "video-gone");
        release(key, "VIDEO", "video-gone", null, "VENDOR_GONE");

        assertThat(lookup(key, "VIDEO",
                List.of("video-mine", "video-theirs", "video-gone", "video-none")))
                .containsExactly("video-mine");
        assertThat(lookup(key, "FILE", List.of("video-mine"))).isEmpty();
        // An unknown key is indistinguishable from a key that owns nothing.
        call("/internal/llm/objects/lookup",
                lookupBody(UUID.randomUUID(), "VIDEO", List.of("video-mine")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owned").isEmpty());
    }

    @Test
    void lookupRefusesMoreThanAHundredIds() throws Exception {
        Key key = fundedKey("many");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add("file-" + i);
        }
        call("/internal/llm/objects/lookup", lookupBody(key.publicId(), "FILE", ids))
                .andExpect(status().isUnprocessableContent());
    }

    // ── release ─────────────────────────────────────────────────────────────

    @Test
    void releaseEndsOwnershipOnceAndOnlyForTheOwner() throws Exception {
        Key key = fundedKey("rel");
        Key other = keyOnAccount("rel-other", key.accountId());
        bind(key, intent(key, "FILE"), "FILE", "file-rel");

        assertThat(release(other, "FILE", "file-rel", null, "DELETED")).isFalse();
        assertThat(release(key, "FILE", "file-rel", null, "DELETED")).isTrue();
        assertThat(release(key, "FILE", "file-rel", null, "DELETED")).isFalse();
        assertThat(lookup(key, "FILE", List.of("file-rel"))).isEmpty();

        // The vendor id is free again once released.
        bind(other, intent(other, "FILE"), "FILE", "file-rel");
        assertThat(lookup(other, "FILE", List.of("file-rel"))).containsExactly("file-rel");
    }

    @Test
    void releaseOfAnIntentTheVendorRefused() throws Exception {
        Key key = fundedKey("refused");
        UUID intentId = intent(key, "BATCH");

        assertThat(release(key, "BATCH", null, intentId, "NOT_CREATED")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select release_reason from llm_async_objects where public_id = ?",
                String.class, intentId)).isEqualTo("NOT_CREATED");
        bindCall(key.publicId(), intentId, "BATCH", "batch-late").andExpect(status().isNotFound());
    }

    @Test
    void releaseNeedsExactlyOneTargetMatchingItsReason() throws Exception {
        Key key = fundedKey("target");
        UUID intentId = intent(key, "FILE");

        releaseCall(key, "FILE", "file-x", intentId, "DELETED")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RELEASE_TARGET_INVALID"));
        releaseCall(key, "FILE", null, null, "DELETED")
                .andExpect(status().isUnprocessableContent());
        releaseCall(key, "FILE", "file-x", null, "NOT_CREATED")
                .andExpect(status().isUnprocessableContent());
        releaseCall(key, "FILE", null, intentId, "VENDOR_GONE")
                .andExpect(status().isUnprocessableContent());
    }

    // ── late bind over the usage channel ───────────────────────────────────

    @Test
    void usageMemberBindsAPendingIntentLateAndOnRedelivery() throws Exception {
        Key key = fundedKey("late");
        UUID intentId = intent(key, "BATCH");
        Map<String, Object> event = usageEvent(key, "evt-late-" + intentId,
                member("BATCH", intentId, "batch-late-1", false, "2026-12-09T00:00:00Z"));

        usage(event).andExpect(jsonPath("$.accepted").value(1));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select state, vendor_object_id, bound_late, expires_at is not null as expiring
                from llm_async_objects where public_id = ?
                """, intentId);
        assertThat(row.get("state")).isEqualTo("OWNED");
        assertThat(row.get("vendor_object_id")).isEqualTo("batch-late-1");
        assertThat(row.get("bound_late")).isEqualTo(true);
        assertThat(row.get("expiring")).isEqualTo(true);
        assertThat(lookup(key, "BATCH", List.of("batch-late-1"))).containsExactly("batch-late-1");

        usage(event).andExpect(jsonPath("$.duplicates").value(1));
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void usageMemberAfterASynchronousBindChangesNothing() throws Exception {
        Key key = fundedKey("sync-then-late");
        UUID intentId = intent(key, "VIDEO");
        bind(key, intentId, "VIDEO", "gen-vid-1");

        usage(usageEvent(key, "evt-sync-" + intentId,
                member("VIDEO", intentId, "gen-vid-1", false, null)))
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "select bound_late from llm_async_objects where public_id = ?",
                Boolean.class, intentId)).isFalse();
    }

    @Test
    void compensatedSubmitLandsReleased() throws Exception {
        Key key = fundedKey("compensated");
        UUID intentId = intent(key, "BATCH");
        usage(usageEvent(key, "evt-comp-" + intentId,
                member("BATCH", intentId, "batch-undone", true, null)));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select state, release_reason, bound_late from llm_async_objects where public_id = ?",
                intentId);
        assertThat(row.get("state")).isEqualTo("RELEASED");
        assertThat(row.get("release_reason")).isEqualTo("COMPENSATED");
        assertThat(row.get("bound_late")).isEqualTo(true);
        assertThat(lookup(key, "BATCH", List.of("batch-undone"))).isEmpty();
    }

    /** A restore loses the intent row too; the replayed event recreates it. */
    @Test
    void replayAfterARestoreRecreatesTheRow() throws Exception {
        Key key = fundedKey("restore");
        UUID lostIntent = UUID.randomUUID();
        usage(usageEvent(key, "evt-restore-" + lostIntent,
                member("VIDEO", lostIntent, "gen-vid-restored", false, null)))
                .andExpect(jsonPath("$.accepted").value(1));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select key_id, state, bound_late from llm_async_objects where public_id = ?",
                lostIntent);
        assertThat(row.get("key_id")).isEqualTo(key.id());
        assertThat(row.get("state")).isEqualTo("OWNED");
        assertThat(row.get("bound_late")).isEqualTo(true);
        assertThat(lookup(key, "VIDEO", List.of("gen-vid-restored")))
                .containsExactly("gen-vid-restored");
    }

    @Test
    void anotherKeysObjectLeavesTheIntentPending() throws Exception {
        Key owner = fundedKey("taken-owner");
        Key other = keyOnAccount("taken-other", owner.accountId());
        bind(owner, intent(owner, "BATCH"), "BATCH", "batch-shared");
        UUID intentId = intent(other, "BATCH");

        usage(usageEvent(other, "evt-taken-" + intentId,
                member("BATCH", intentId, "batch-shared", false, null)))
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "select state from llm_async_objects where public_id = ?", String.class, intentId))
                .isEqualTo("PENDING");
        assertThat(lookup(other, "BATCH", List.of("batch-shared"))).isEmpty();
    }

    @Test
    void usageMemberForAnObjectTheKeyAlreadyOwnsReleasesTheIntentAsDuplicate() throws Exception {
        Key key = fundedKey("late-duplicate");
        bind(key, intent(key, "BATCH"), "BATCH", "batch-same");
        UUID second = intent(key, "BATCH");
        usage(usageEvent(key, "evt-dup-" + second, member("BATCH", second, "batch-same", false, null)));
        assertThat(jdbcTemplate.queryForMap(
                "select state, release_reason from llm_async_objects where public_id = ?", second))
                .containsEntry("state", "RELEASED").containsEntry("release_reason", "DUPLICATE");
        assertThat(lookup(key, "BATCH", List.of("batch-same"))).containsExactly("batch-same");
    }

    /** Every submit writes an intent, so a member without one recreates nothing. */
    @Test
    void usageMemberWithoutAnIntentRecreatesNothing() throws Exception {
        Key key = fundedKey("no-intent");
        usage(usageEvent(key, "evt-nointent-" + key.publicId(),
                Map.of("kind", "VIDEO", "objectId", "gen-vid-orphan", "compensated", false)))
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(rows()).isZero();
    }

    /** An intent id that already names another key's row is not taken over. */
    @Test
    void usageMemberNamingAnotherKeysIntentRecreatesNothing() throws Exception {
        Key owner = fundedKey("intent-owner");
        Key other = keyOnAccount("intent-other", owner.accountId());
        UUID ownersIntent = intent(owner, "BATCH");
        usage(usageEvent(other, "evt-steal-" + ownersIntent,
                member("BATCH", ownersIntent, "batch-stolen", false, null)))
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(rows()).isEqualTo(1);
        assertThat(stateOf(ownersIntent)).isEqualTo("PENDING");
        assertThat(lookup(other, "BATCH", List.of("batch-stolen"))).isEmpty();
    }

    /** A member nobody can use costs the ledger nothing and the usage row nothing. */
    @Test
    void unusableMembersKeepTheirEvents() throws Exception {
        Key key = fundedKey("malformed");
        UUID intentId = intent(key, "BATCH");
        Map<String, Object> notAnObject = usageEvent(key, "evt-bad-1-" + intentId, "nonsense");
        Map<String, Object> unknownKind = usageEvent(key, "evt-bad-2-" + intentId,
                member("CONTAINER", intentId, "c-1", false, null));
        Map<String, Object> badIntent = usageEvent(key, "evt-bad-3-" + intentId,
                Map.of("kind", "BATCH", "intentId", "not-a-uuid", "objectId", "b-1"));
        Map<String, Object> noObjectId = usageEvent(key, "evt-bad-4-" + intentId,
                Map.of("kind", "BATCH", "intentId", intentId.toString()));

        call("/internal/llm/usage", Map.of("agentVersion", "test",
                "events", List.of(notAnObject, unknownKind, badIntent, noObjectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(4))
                .andExpect(jsonPath("$.rejected").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "select state from llm_async_objects where public_id = ?", String.class, intentId))
                .isEqualTo("PENDING");
    }

    // ── sweep ───────────────────────────────────────────────────────────────

    @Test
    void sweepReleasesExpiredBatchesAndForgetsStaleRows() throws Exception {
        Key key = fundedKey("sweep");
        UUID expired = intent(key, "BATCH");
        bind(key, expired, "BATCH", "batch-expired");
        jdbcTemplate.update("update llm_async_objects set expires_at = now() - interval '1 hour' "
                + "where public_id = ?", expired);
        UUID live = intent(key, "BATCH");
        bind(key, live, "BATCH", "batch-live");
        jdbcTemplate.update("update llm_async_objects set expires_at = now() + interval '1 day' "
                + "where public_id = ?", live);
        UUID video = intent(key, "VIDEO");
        bind(key, video, "VIDEO", "gen-vid-old");
        jdbcTemplate.update("update llm_async_objects set created_at = now() - interval '400 days' "
                + "where public_id = ?", video);
        UUID stalePending = intent(key, "BATCH");
        jdbcTemplate.update("update llm_async_objects set created_at = now() - interval '31 days' "
                + "where public_id = ?", stalePending);
        UUID freshPending = intent(key, "BATCH");
        UUID oldVideoPending = intent(key, "VIDEO");
        jdbcTemplate.update("update llm_async_objects set created_at = now() - interval '31 days' "
                + "where public_id = ?", oldVideoPending);
        UUID oldReleased = intent(key, "BATCH");
        release(key, "BATCH", null, oldReleased, "NOT_CREATED");
        jdbcTemplate.update("update llm_async_objects set released_at = now() - interval '91 days' "
                + "where public_id = ?", oldReleased);

        sweeper.sweep();

        assertThat(jdbcTemplate.queryForMap(
                "select state, release_reason from llm_async_objects where public_id = ?", expired))
                .containsEntry("state", "RELEASED").containsEntry("release_reason", "VENDOR_GONE");
        assertThat(stateOf(live)).isEqualTo("OWNED");
        assertThat(stateOf(video)).isEqualTo("OWNED");
        assertThat(stateOf(freshPending)).isEqualTo("PENDING");
        assertThat(stateOf(oldVideoPending)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_async_objects where public_id in (?, ?)",
                Integer.class, stalePending, oldReleased)).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String stateOf(UUID intentId) {
        return jdbcTemplate.queryForObject(
                "select state from llm_async_objects where public_id = ?", String.class, intentId);
    }

    private static Map<String, Object> member(String kind, UUID intentId, String objectId,
            boolean compensated, String expiresAt) {
        Map<String, Object> member = new HashMap<>();
        member.put("kind", kind);
        member.put("intentId", intentId.toString());
        member.put("objectId", objectId);
        member.put("compensated", compensated);
        if (expiresAt != null) {
            member.put("expiresAt", expiresAt);
        }
        return member;
    }

    private static Map<String, Object> usageEvent(Key key, String eventId, Object object) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventUuid", eventId.length() > 64 ? eventId.substring(0, 64) : eventId);
        event.put("keyId", key.publicId().toString());
        event.put("budgetAxis", "CREDIT");
        event.put("status", "OK");
        event.put("inputTokens", 0);
        event.put("outputTokens", 0);
        event.put("latencyMs", 5);
        event.put("requestedAt", java.time.Instant.now().toString());
        event.put("endpoint", "batches");
        event.put("object", object);
        return event;
    }

    private ResultActions usage(Map<String, Object> event) throws Exception {
        return call("/internal/llm/usage", Map.of("agentVersion", "test", "events", List.of(event)))
                .andExpect(status().isOk());
    }

    private UUID intent(Key key, String kind) throws Exception {
        String body = intentCall(key.publicId(), kind).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("intentId").asString());
    }

    private ResultActions intentCall(UUID keyId, String kind) throws Exception {
        return call("/internal/llm/objects/intents", Map.of("agentVersion", "test",
                "keyId", keyId.toString(), "kind", kind, "eventId", "evt-" + keyId));
    }

    private void bind(Key key, UUID intentId, String kind, String objectId) throws Exception {
        bindCall(key.publicId(), intentId, kind, objectId).andExpect(status().isOk());
    }

    private ResultActions bindCall(UUID keyId, UUID intentId, String kind, String objectId)
            throws Exception {
        return call("/internal/llm/objects/bind", Map.of("keyId", keyId.toString(),
                "intentId", intentId.toString(), "kind", kind, "objectId", objectId,
                "sizeBytes", 42));
    }

    private List<String> lookup(Key key, String kind, List<String> ids) throws Exception {
        String body = call("/internal/llm/objects/lookup", lookupBody(key.publicId(), kind, ids))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> owned = new ArrayList<>();
        for (JsonNode id : objectMapper.readTree(body).get("owned")) {
            owned.add(id.asString());
        }
        return owned;
    }

    private static Map<String, Object> lookupBody(UUID keyId, String kind, List<String> ids) {
        return Map.of("keyId", keyId.toString(), "kind", kind, "objectIds", ids);
    }

    private boolean release(Key key, String kind, String objectId, UUID intentId, String reason)
            throws Exception {
        String body = releaseCall(key, kind, objectId, intentId, reason).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("released").asBoolean();
    }

    private ResultActions releaseCall(Key key, String kind, String objectId, UUID intentId,
            String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("keyId", key.publicId().toString());
        body.put("kind", kind);
        body.put("reason", reason);
        if (objectId != null) {
            body.put("objectId", objectId);
        }
        if (intentId != null) {
            body.put("intentId", intentId.toString());
        }
        return call("/internal/llm/objects/release", body);
    }

    private ResultActions call(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).with(remoteAddr(SOURCE))
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private int rows() {
        return jdbcTemplate.queryForObject("select count(*) from llm_async_objects", Integer.class);
    }

    private Key fundedKey(String slug) {
        long accountId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                returning id
                """, Long.class, SeedFixtures.seedOrgId(jdbcTemplate),
                "소유 원장 시험 사업 " + UUID.randomUUID(), SeedFixtures.orgadminId(jdbcTemplate));
        accountIds.add(accountId);
        return keyOnAccount(slug, accountId);
    }

    private Key keyOnAccount(String slug, long accountId) {
        Key key = key(slug);
        jdbcTemplate.update("update llm_api_keys set openrouter_account_id = ? where id = ?",
                accountId, key.id());
        return new Key(key.id(), key.publicId(), accountId);
    }

    private Key key(String slug) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT'::workspace_kind, ?)
                returning id
                """, Long.class, "소유 원장 시험 " + slug + "-" + unique);
        workspaceIds.add(workspaceId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "소유 원장 시험", "llm-" + slug);
        requestIds.add(requestId);
        String plaintext = LlmApiKeyTokens.newToken();
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name,
                                          token_hash, token_prefix, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE'::llm_api_key_status, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + slug,
                LlmApiKeyTokens.hash(plaintext), LlmApiKeyTokens.visiblePrefix(plaintext), ownerId);
        keyIds.add(id);
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, id);
        return new Key(id, publicId, null);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private record Key(long id, UUID publicId, Long accountId) {
    }
}
