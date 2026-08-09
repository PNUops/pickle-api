package kr.ac.pusan.pickle.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * Relay sync surface: per-relay auth (source pin + hashed token binding,
 * fail-closed), the source-route restriction, its own rate-limit scope, body
 * cap, report sanitization, generation validation, the single-view snapshot
 * semantics (unchanged answers omit {@code mappings} entirely; SUSPENDED rows
 * never appear), reset-aware counters and the threshold auto-suspend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RelaySyncEndpointTest {

    /** The seed relay's tunnel address = the restricted source (defaults). */
    private static final String RESTRICTED_SOURCE = "10.100.100.1";
    private static final AtomicInteger SOURCE_SEQ = new AtomicInteger(1);
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(901_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.relay.sync-rate-limit-per-minute", () -> "5");
        // Room for a full-size counter report (the volume tests below); the
        // cap itself is still exercised by an oversized body.
        registry.add("pickle.relay.max-sync-body-bytes", () -> "262144");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── auth ────────────────────────────────────────────────────────────────

    @Test
    void wrongTokenAnswers401() throws Exception {
        RelayFixture relay = newRelay("token-a");
        sync(relay.id(), relay.sourceIp(), "not-the-token", Map.of("appliedGeneration", 0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void relayTokenIsBoundToItsOwnPath() throws Exception {
        RelayFixture relayA = newRelay("bind-a");
        RelayFixture relayB = newRelay("bind-b");
        // A's (valid) token presented on B's path from B's source: the token
        // is compared against B's own hash, so it must be refused.
        sync(relayB.id(), relayB.sourceIp(), relayA.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        sync(relayB.id(), relayB.sourceIp(), relayB.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isOk());
    }

    @Test
    void wrongSourceAnswers403EvenWithTheRightToken() throws Exception {
        RelayFixture relay = newRelay("source");
        sync(relay.id(), "203.0.113.77", relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void nullTokenHashFailsClosed() throws Exception {
        RelayFixture relay = newRelay("no-token");
        jdbcTemplate.update("update relays set token_hash = null where id = ?", relay.id());
        sync(relay.id(), relay.sourceIp(), "any-value", Map.of("appliedGeneration", 0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void disabledRelayAnswersGeneric403() throws Exception {
        RelayFixture relay = newRelay("disabled");
        jdbcTemplate.update("update relays set enabled = false where id = ?", relay.id());
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ── source-route restriction (the tunnel address reaches sync only) ────

    @Test
    void restrictedSourceIsConfinedToTheSyncSurface() throws Exception {
        // The relay tunnel address must not reach anything but its sync
        // surface: actuator and the public API answer 403 before any chain.
        mockMvc.perform(get("/actuator/health").with(remoteAddr(RESTRICTED_SOURCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/meta/status").with(remoteAddr(RESTRICTED_SOURCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // The sync surface itself passes the restriction and lands in the
        // relay auth filter (the seed relay has no token issued -> 401).
        // Re-arm the seed row explicitly: another suite may have disabled it.
        jdbcTemplate.update("""
                update relays set enabled = true, token_hash = null
                 where name = 'lightsail-1'
                """);
        long seedRelayId = jdbcTemplate.queryForObject(
                "select id from relays where name = 'lightsail-1'", Long.class);
        sync(seedRelayId, RESTRICTED_SOURCE, "whatever", Map.of("appliedGeneration", 0))
                .andExpect(status().isUnauthorized());
        // Unrestricted sources keep full access.
        mockMvc.perform(get("/api/v1/meta/status")).andExpect(status().isOk());
    }

    @Test
    void restrictionDecidesOnTheNormalizedPath() throws Exception {
        // Traversal and encoding variants must never look like the sync
        // surface: the confinement normalizes before matching (self-contained,
        // not delegated to StrictHttpFirewall).
        mockMvc.perform(get("/internal/relays/../actuator/health")
                        .with(remoteAddr(RESTRICTED_SOURCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/internal/relays/%2e%2e/actuator/health")
                        .with(remoteAddr(RESTRICTED_SOURCE)))
                .andExpect(status().isForbidden());

        // The normalization itself, exhaustively (fail-closed variants).
        assertThat(RelaySourceRestrictionFilter.isRelaySurface("/internal/relays/1/sync"))
                .isTrue();
        assertThat(RelaySourceRestrictionFilter.isRelaySurface("/internal/relays/../actuator"))
                .isFalse();
        assertThat(RelaySourceRestrictionFilter.isRelaySurface(
                "/internal/relays/%2e%2e/actuator")).isFalse();
        assertThat(RelaySourceRestrictionFilter.isRelaySurface(
                "/internal/relays;a=b/1/sync")).isFalse();
        assertThat(RelaySourceRestrictionFilter.isRelaySurface("/internal/relays/%zz"))
                .isFalse();
        assertThat(RelaySourceRestrictionFilter.isRelaySurface("/../internal/relays/1/sync"))
                .isFalse();
        // double encoding: one decode leaves a %, which is refused outright
        assertThat(RelaySourceRestrictionFilter.isRelaySurface(
                "/internal/relays/%252e%252e/actuator")).isFalse();
    }

    // ── rate limit (own scope, never the sshgw bucket) ──────────────────────

    @Test
    void syncRateLimitUsesItsOwnScope() throws Exception {
        RelayFixture relay = newRelay("ratelimit");
        for (int i = 0; i < 5; i++) {
            sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                    .andExpect(status().isOk());
        }
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        Long relayScope = jdbcTemplate.queryForObject("""
                select count(*) from auth_rate_limits
                 where scope = 'relay_sync' and subject = ?
                """, Long.class, "relay:" + relay.id());
        Long sshgwScope = jdbcTemplate.queryForObject(
                "select count(*) from auth_rate_limits where scope = 'sshgw_route_global'",
                Long.class);
        assertThat(relayScope).isPositive();
        assertThat(sshgwScope).isZero(); // never the shared sshgw bucket
    }

    // ── body cap ────────────────────────────────────────────────────────────

    @Test
    void oversizedBodyAnswers413() throws Exception {
        RelayFixture relay = newRelay("bodycap");
        String padding = "x".repeat(300_000);
        mockMvc.perform(post("/internal/relays/" + relay.id() + "/sync")
                        .with(remoteAddr(relay.sourceIp()))
                        .header("Authorization", "Bearer " + relay.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedGeneration\":0,\"agentVersion\":\"" + padding + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    // ── report sanitization ─────────────────────────────────────────────────

    @Test
    void reportedStringsAreControlStrippedAndTruncated() throws Exception {
        RelayFixture relay = newRelay("sanitize");
        String ansiVersion = "v1.2.3\u001b[31mRED\u001b[0m\r\n";
        String longMessage = "e".repeat(2000) + "tail";
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0,
                "agentVersion", ansiVersion,
                "lastError", List.of(Map.of("mappingId", 7, "message", longMessage))))
                .andExpect(status().isOk());
        String storedVersion = jdbcTemplate.queryForObject(
                "select agent_version from relays where id = ?", String.class, relay.id());
        assertThat(storedVersion).isEqualTo("v1.2.3[31mRED[0m"); // ESC/CR/LF gone
        String storedError = jdbcTemplate.queryForObject(
                "select last_error from relays where id = ?", String.class, relay.id());
        assertThat(storedError).doesNotContain("\u001b").contains("\"mappingId\":7");
        String message = objectMapper.readTree(storedError).get(0).get("message").asString();
        assertThat(message).hasSizeLessThanOrEqualTo(1024).doesNotContain("tail");
    }

    // ── generation validation ───────────────────────────────────────────────

    @Test
    void impossibleAppliedGenerationIsAuditedAndNotPersisted() throws Exception {
        RelayFixture relay = newRelay("violation");
        // current generation is 0 — reporting 5 is impossible
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 5))
                .andExpect(status().isOk())
                // the violation forces a full snapshot (mappings present, empty)
                .andExpect(jsonPath("$.mappings").isArray());
        Long storedApplied = jdbcTemplate.queryForObject(
                "select applied_generation from relays where id = ?", Long.class, relay.id());
        assertThat(storedApplied).isZero(); // the reported value never lands
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'relay.sync_violation' and target_id = ?
                """, Long.class, relay.id());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void regressingAppliedGenerationIsAViolation() throws Exception {
        RelayFixture relay = newRelay("regress");
        jdbcTemplate.update("""
                update relays set mapping_generation = 4, applied_generation = 3 where id = ?
                """, relay.id());
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 1))
                .andExpect(status().isOk());
        Long storedApplied = jdbcTemplate.queryForObject(
                "select applied_generation from relays where id = ?", Long.class, relay.id());
        assertThat(storedApplied).isEqualTo(3); // kept, not regressed
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'relay.sync_violation' and target_id = ?
                """, Long.class, relay.id());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void aReportOfGenerationZeroIsTreatedAsARestart() throws Exception {
        RelayFixture relay = newRelay("restart");
        jdbcTemplate.update("""
                update relays set mapping_generation = 4, applied_generation = 3 where id = ?
                """, relay.id());
        // An agent that boots without a usable snapshot honestly reports 0.
        // That is routine, so it must not raise a security signal — but the
        // stored progress is still kept and the full snapshot still answered.
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappings").isArray());
        Long storedApplied = jdbcTemplate.queryForObject(
                "select applied_generation from relays where id = ?", Long.class, relay.id());
        assertThat(storedApplied).isEqualTo(3);
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'relay.sync_violation' and target_id = ?
                """, Long.class, relay.id());
        assertThat(audits).isZero();
    }

    // ── snapshot semantics ──────────────────────────────────────────────────

    @Test
    void unchangedStateOmitsTheMappingsFieldEntirely() throws Exception {
        RelayFixture relay = newRelay("tiny");
        String body = sync(relay.id(), relay.sourceIp(), relay.token(),
                Map.of("appliedGeneration", 0))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Raw-JSON assertion on purpose: the field must be ABSENT (the agent
        // treats presence as "replace your table"), not null or [].
        assertThat(body).isEqualTo("{\"generation\":0}");
    }

    @Test
    void changedStateCarriesTheFullSnapshotWithLiveTargets() throws Exception {
        RelayFixture relay = newRelay("snapshot");
        long vmId = runningVm();
        String vmIp = vmIp(vmId);
        insertMapping(relay.id(), vmId, "TCP", 12345, 8080, "ACTIVE", 1);
        jdbcTemplate.update("update relays set mapping_generation = 1 where id = ?", relay.id());
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.mappings.length()").value(1))
                // lowercase on the internal wire (frozen record), TCP in the DB
                .andExpect(jsonPath("$.mappings[0].proto").value("tcp"))
                .andExpect(jsonPath("$.mappings[0].publicPort").value(12345))
                .andExpect(jsonPath("$.mappings[0].targetAddr").value(vmIp))
                .andExpect(jsonPath("$.mappings[0].targetPort").value(8080))
                // guard columns are null -> the fields are omitted
                .andExpect(jsonPath("$.mappings[0].ctMax").doesNotExist());
    }

    @Test
    void suspendedMappingsAreExcludedFromTheSnapshot() throws Exception {
        RelayFixture relay = newRelay("suspended");
        long vmId = runningVm();
        insertMapping(relay.id(), vmId, "TCP", 13001, 80, "ACTIVE", 1);
        insertMapping(relay.id(), vmId, "TCP", 13002, 81, "SUSPENDED", 2);
        jdbcTemplate.update("update relays set mapping_generation = 2 where id = ?", relay.id());
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of("appliedGeneration", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappings.length()").value(1))
                .andExpect(jsonPath("$.mappings[0].publicPort").value(13001));
    }

    // ── counters ────────────────────────────────────────────────────────────

    @Test
    void countersAccumulateWithResetDetection() throws Exception {
        RelayFixture relay = newRelay("counters");
        long vmId = runningVm();
        long mappingId = insertMapping(relay.id(), vmId, "UDP", 14001, 5000, "ACTIVE", 1);
        jdbcTemplate.update("update relays set mapping_generation = 1 where id = ?", relay.id());

        syncCounters(relay, mappingId, 100, 1000, 500); // baseline
        assertCounterTotals(mappingId, 100, 1500);
        syncCounters(relay, mappingId, 150, 1200, 600); // monotonic delta
        assertCounterTotals(mappingId, 150, 1800);
        // A decrease ALWAYS means agent restart: re-baseline, never negative.
        syncCounters(relay, mappingId, 50, 100, 40);
        assertCounterTotals(mappingId, 200, 1940);
    }

    @Test
    void counterRowsForForeignMappingsAreIgnored() throws Exception {
        RelayFixture relayA = newRelay("foreign-a");
        RelayFixture relayB = newRelay("foreign-b");
        long vmId = runningVm();
        long mappingOfB = insertMapping(relayB.id(), vmId, "TCP", 15001, 80, "ACTIVE", 1);
        syncCounters(relayA, mappingOfB, 999, 999, 999); // reported via relay A
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from port_mapping_counters where mapping_id = ?",
                Long.class, mappingOfB);
        assertThat(rows).isZero(); // relay A cannot pollute B's accounting
    }

    @Test
    void insaneCounterReadingsAreDiscardedAndAudited() throws Exception {
        RelayFixture relay = newRelay("sanity");
        long vmId = runningVm();
        long mappingId = insertMapping(relay.id(), vmId, "TCP", 14500, 80, "ACTIVE", 1);
        // beyond the 2^53 sanity ceiling: the whole reading is discarded (no
        // totals, no baseline) and the event is audited — bigint totals can
        // never be marched toward overflow by a lying agent.
        syncCounters(relay, mappingId, (1L << 53) + 1, 0, 0);
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from port_mapping_counters where mapping_id = ?",
                Long.class, mappingId);
        assertThat(rows).isZero();
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'relay.sync_violation' and target_id = ?
                   and detail ->> 'kind' = 'counter_sanity'
                """, Long.class, relay.id());
        assertThat(audits).isEqualTo(1);
        // a sane follow-up reading baselines normally
        syncCounters(relay, mappingId, 10, 100, 100);
        assertCounterTotals(mappingId, 10, 200);
    }

    @Test
    void aLargeCounterReportIsAcceptedNotRejected() throws Exception {
        // The agent reports one row per live mapping, so a busy relay easily
        // exceeds any small row count. Rejecting the body would take the
        // desired-state channel (suspend, delete, last contact) down with it,
        // so volume never fails the request.
        RelayFixture relay = newRelay("volume");
        long vmId = runningVm();
        long mappingId = insertMapping(relay.id(), vmId, "TCP", 17001, 80, "ACTIVE", 1);
        List<Map<String, Object>> rows = new ArrayList<>(paddingRows(199));
        rows.add(counterRow(mappingId, 100, 1000, 500));
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0, "counters", rows))
                .andExpect(status().isOk());
        assertCounterTotals(mappingId, 100, 1500);
    }

    @Test
    void countersPastTheServerCapAreIgnoredWithoutFailingTheRequest() throws Exception {
        RelayFixture relay = newRelay("countercap");
        long vmId = runningVm();
        long mappingId = insertMapping(relay.id(), vmId, "TCP", 17101, 80, "ACTIVE", 1);
        List<Map<String, Object>> padding = paddingRows(RelaySyncService.MAX_REPORTED_COUNTERS);

        // beyond the cap: the row is dropped, the request still succeeds
        List<Map<String, Object>> tooLate = new ArrayList<>(padding);
        tooLate.add(counterRow(mappingId, 100, 1000, 500));
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0, "counters", tooLate))
                .andExpect(status().isOk());
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from port_mapping_counters where mapping_id = ?",
                Long.class, mappingId);
        assertThat(rows).isZero();

        // the same row inside the cap is accounted for normally
        List<Map<String, Object>> inTime = new ArrayList<>();
        inTime.add(counterRow(mappingId, 100, 1000, 500));
        inTime.addAll(padding);
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0, "counters", inTime))
                .andExpect(status().isOk());
        assertCounterTotals(mappingId, 100, 1500);
    }

    /** {@code count} counter rows for mapping ids no relay owns. */
    private static List<Map<String, Object>> paddingRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(Map.of("mappingId", 8_000_000L + i, "newConns", 1));
        }
        return rows;
    }

    @Test
    void thresholdBreachAutoSuspendsInTheSameResponse() throws Exception {
        RelayFixture relay = newRelay("autosuspend");
        long vmId = runningVm();
        long mappingId = insertMapping(relay.id(), vmId, "UDP", 16001, 53, "ACTIVE", 1);
        jdbcTemplate.update("update relays set mapping_generation = 1 where id = ?", relay.id());

        syncCounters(relay, mappingId, 0, 0, 0); // baseline (no rate yet)
        // Huge delta over a sub-second window -> conns/min far above the
        // default 6000 threshold -> suspended in the SAME transaction, so the
        // snapshot of this very response already excludes the mapping.
        String body = sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0,
                "counters", List.of(counterRow(mappingId, 500_000, 0, 0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("mappings")).isNotNull();
        assertThat(objectMapper.readTree(body).get("mappings")).isEmpty();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, suspended_reason, suspended_by, last_change_generation "
                        + "from port_mappings where id = ?", mappingId);
        assertThat(row.get("status")).isEqualTo("SUSPENDED");
        assertThat(String.valueOf(row.get("suspended_reason"))).contains("자동 정지");
        assertThat(row.get("suspended_by")).isNull();
        assertThat(((Number) row.get("last_change_generation")).longValue()).isEqualTo(2);

        long sysadminId = SeedFixtures.sysadminId(jdbcTemplate);
        Long notified = jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and event = 'port_mapping.suspended'
                   and dedup_key = ?
                """, Long.class, sysadminId, "pm_auto_suspend:" + mappingId);
        assertThat(notified).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record RelayFixture(long id, String sourceIp, String token) {
    }

    /** Fresh enabled relay with an issued token and its own source address. */
    private RelayFixture newRelay(String slug) {
        String sourceIp = "198.51.100." + SOURCE_SEQ.getAndIncrement();
        String token = "relay-test-token-" + UUID.randomUUID();
        long id = jdbcTemplate.queryForObject("""
                insert into relays (name, source_ip, token_hash, port_band_start, port_band_end)
                values (?, ?, ?, 10000, 19999)
                returning id
                """, Long.class, "test-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8),
                sourceIp, sha256Hex(token));
        return new RelayFixture(id, sourceIp, token);
    }

    private ResultActions sync(long relayId, String sourceIp, String token, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/internal/relays/" + relayId + "/sync")
                .with(remoteAddr(sourceIp))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void syncCounters(RelayFixture relay, long mappingId, long newConns, long inBytes,
            long outBytes) throws Exception {
        sync(relay.id(), relay.sourceIp(), relay.token(), Map.of(
                "appliedGeneration", 0,
                "counters", List.of(counterRow(mappingId, newConns, inBytes, outBytes))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> counterRow(long mappingId, long newConns, long inBytes,
            long outBytes) {
        return Map.of("mappingId", mappingId, "newConns", newConns, "inBytes", inBytes,
                "outBytes", outBytes, "inPackets", 0, "outPackets", 0, "rateDropped", 0,
                "connDropped", 0, "perSourceDropped", 0);
    }

    private void assertCounterTotals(long mappingId, long connTotal, long bytesTotal) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select conn_total, bytes_total from port_mapping_counters where mapping_id = ?",
                mappingId);
        assertThat(((Number) row.get("conn_total")).longValue()).isEqualTo(connTotal);
        assertThat(((Number) row.get("bytes_total")).longValue()).isEqualTo(bytesTotal);
    }

    private long insertMapping(long relayId, long vmId, String proto, int publicPort,
            int targetPort, String status, long generation) {
        return jdbcTemplate.queryForObject("""
                insert into port_mappings (relay_id, vm_id, proto, public_port, target_port,
                                           status, last_change_generation, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, relayId, vmId, proto, publicPort, targetPort, status,
                generation, SeedFixtures.sysadminId(jdbcTemplate));
    }

    /** Minimal RUNNING VM with a live allocation (direct rows, no API dance). */
    private long runningVm() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "rly-" + UUID.randomUUID().toString().substring(0, 10);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name, slug) values ('TEAM'::workspace_kind, ?, ?)
                returning id
                """, Long.class, "릴레이 테스트 " + slug, slug);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, ownerId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (workspace_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '릴레이 동기화 테스트', (select min(id) from os_images),
                        1, 1024, 10)
                returning id
                """, Long.class, workspaceId, orgId, ownerId);
        String hostname = "rly-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, workspaceId, orgId, requestId, hostname, hostname,
                VMID_SEQ.incrementAndGet());
        String ip = "172.29.210." + IP_SEQ.getAndIncrement();
        long allocId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name = 'guest-private'), ?::inet, ?,
                        'ALLOCATED')
                returning id
                """, Long.class, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocId, vmId);
        return vmId;
    }

    private String vmIp(long vmId) {
        String ip = jdbcTemplate.queryForObject("""
                select host(a.ip) from ip_allocations a join vms v on v.ip_allocation_id = a.id
                 where v.id = ?
                """, String.class, vmId);
        return ip;
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(
            String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
