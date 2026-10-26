package kr.ac.pusan.pickle.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** Real HTTP, dedicated credentials and a disposable database exercise the sample path. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, InferenceObservationTest.FixedClockConfiguration.class})
class InferenceObservationTest {
    static final UUID SOURCE = UUID.fromString("00000000-0000-4000-8000-000000000101");
    static final UUID UPSTREAM = UUID.fromString("00000000-0000-4000-8000-000000007002");
    static final String PEER = "192.0.2.61";
    static final String TOKEN = "synthetic-observation-reporter-token";
    static final Instant NOW = Instant.parse("2026-10-26T00:02:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MutableClock clock;
    @Autowired ObservationRetention retention;
    @Autowired OrgRepository orgs;
    @Autowired WorkspaceRepository workspaces;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.inference-observations.enabled", () -> "true");
        registry.add("pickle.inference-observations.token", () -> TOKEN);
        registry.add("pickle.inference-observations.allowed-source-ip", () -> PEER);
        registry.add("pickle.inference-observations.source-id", SOURCE::toString);
        registry.add("pickle.inference-observations.upstream-id", UPSTREAM::toString);
        registry.add("pickle.llm-gateway.token", () -> "synthetic-gateway-token");
        registry.add("pickle.llm-gateway.allowed-source-ip", () -> PEER);
        registry.add("pickle.sshgw.token", () -> "synthetic-ssh-token");
        registry.add("pickle.sshgw.allowed-source-ip", () -> PEER);
    }

    @BeforeEach
    void registeredSyntheticUpstream() {
        clock.at = NOW;
        jdbc.update("delete from inference_observation_samples");
        jdbc.update("delete from auth_rate_limits where scope='inference_observation'");
        jdbc.update("""
                insert into llm_upstreams(public_id,ref,kind,display_name)
                values (?,'synthetic-observation','ON_PREM','Synthetic observation source')
                on conflict(public_id) do nothing
                """, UPSTREAM);
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary MutableClock observationTestClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        volatile Instant at = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(at, zone); }
        @Override public Instant instant() { return at; }
    }

    @Test
    void dedicatedReporterStoresAndDeduplicatesOneLogicalSample() throws Exception {
        String body = sample(UUID.randomUUID(), 1);
        JsonNode first = json.readTree(mvc.perform(post("/internal/inference-observations")
                        .with(request -> { request.setRemoteAddr(PEER); return request; })
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString());
        JsonNode duplicate = json.readTree(mvc.perform(post("/internal/inference-observations")
                        .with(request -> { request.setRemoteAddr(PEER); return request; })
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andReturn().getResponse().getContentAsString());
        assertThat(duplicate.get("contentSha256")).isEqualTo(first.get("contentSha256"));
        assertThat(duplicate.get("receivedAt")).isEqualTo(first.get("receivedAt"));
    }

    @Test
    void systemReaderDistinguishesConfiguredButUnreportedWindow() throws Exception {
        User actor = users.save(new User("observation-" + UUID.randomUUID() + "@example.invalid",
                "{test-no-login}", "관측 시험"));
        actor.setRole(UserRole.SYS_VIEWER);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setEmailVerifiedAt(Instant.now());
        actor = users.save(actor);
        mvc.perform(get("/api/v1/admin/llm/capacity")
                        .param("from", NOW.minusSeconds(60).toString()).param("to", NOW.toString())
                        .header("Authorization", "Bearer " + jwt.createAccessToken(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetry.status").value("NOT_REPORTED"))
                .andExpect(jsonPath("$.telemetry.device.sourceId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.telemetry.device.retentionDays").value(90));
    }

    @Test
    void forwardedHeaderCannotTurnAnotherSocketPeerIntoTheReporter() throws Exception {
        mvc.perform(post("/internal/inference-observations")
                        .with(request -> { request.setRemoteAddr("192.0.2.62"); return request; })
                        .header("Authorization", "Bearer " + TOKEN).header("X-Forwarded-For", PEER)
                        .contentType(MediaType.APPLICATION_JSON).content(sample(UUID.randomUUID(), 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactProducerPipelineBytesReachStorageAndWeightedRead() throws Exception {
        consumePipeline("inference-observation-pipeline.json");
    }

    @Test
    void earlierProducerFixtureWithoutOptionalCpuMetricRemainsReadable() throws Exception {
        consumePipeline("inference-observation-pipeline-without-cpu.json");
    }

    private void consumePipeline(String resource) throws Exception {
        JsonNode fixture = fixture(resource);
        for (JsonNode body : fixture.get("requestBodies")) {
            String raw = body.asText();
            JsonNode ack = json.readTree(send(raw).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(ack.get("contentSha256").asText()).isEqualTo(digest(raw));
            assertThat(ack.get("status").asText()).isEqualTo("ACCEPTED");
        }
        assertThat(rows()).isEqualTo(2);
        JsonNode response = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", NOW.toString(), "&telemetrySourceId=" + SOURCE);
        JsonNode device = response.get("telemetry").get("device");
        assertThat(response.get("telemetry").get("status").asText()).isEqualTo("REPORTED");
        assertThat(device.get("evidence").get("freshness").asText()).isEqualTo("FRESH");
        assertThat(device.get("evidence").get("samplesInWindow").asLong()).isEqualTo(2);
        assertThat(device.get("evidence").get("baselineIntervals").asLong()).isEqualTo(1);
        assertThat(metricMean(device, "rates", "vllm:prompt_tokens_total")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metricMean(device, "histogramMeans", "vllm:time_to_first_token_seconds")).isCloseTo(.15, org.assertj.core.data.Offset.offset(1e-9));
        JsonNode host = device.get("series").get(0).get("host");
        assertThat(host.get("gpu_utilization_percent").get("mean").asDouble()).isZero();
        assertThat(host.get("gpu_memory_total_bytes").get("mean").isNull()).isTrue();
        assertThat(host.get("gpu_memory_total_bytes").get("state").asText()).isEqualTo("UNSUPPORTED");
        if ("inference-observation-pipeline.json".equals(resource)) {
            assertThat(host.get("cpu_load1").get("mean").asDouble()).isEqualTo(.125);
            if (Boolean.getBoolean("observation.write-example")) {
                Path output = Path.of(System.getProperty("java.io.tmpdir"), "inference-observation-example.json");
                Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(response.get("telemetry")));
                Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        }
    }

    @Test
    void reserializedLogicalContentConflictsRatherThanOverwritingBytes() throws Exception {
        String body = sample(UUID.randomUUID(), 1);
        send(body).andExpect(status().isOk());
        send(" " + body).andExpect(status().isConflict());
        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select content_sha256 from inference_observation_samples", String.class)).isEqualTo(digest(body));
    }

    @Test
    void concurrentIdenticalDeliveryHasOneInsertAndOneDuplicate() throws Exception {
        String body = sample(UUID.randomUUID(), 1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> send(body).andReturn().getResponse());
            var second = pool.submit(() -> send(body).andReturn().getResponse());
            var a = first.get(); var b = second.get();
            assertThat(a.getStatus()).isEqualTo(200); assertThat(b.getStatus()).isEqualTo(200);
            assertThat(List.of(json.readTree(a.getContentAsString()).get("status").asText(),
                    json.readTree(b.getContentAsString()).get("status").asText())).containsExactlyInAnyOrder("ACCEPTED", "DUPLICATE");
        }
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void intervalMeansUseElapsedAndObservationWeightsRatherThanAveragesOfMeans() throws Exception {
        JsonNode fixture = fixture("inference-observation-pipeline.json"); clock.at = NOW.plusSeconds(180);
        for (JsonNode body : fixture.get("requestBodies")) send(body.asText()).andExpect(status().isOk());
        ObjectNode third = (ObjectNode) json.readTree(fixture.get("requestBodies").get(1).asText());
        Instant at = Instant.parse("2026-10-26T00:03:00.125Z"); setObserved(third, at);
        third.put("sequence", 3); third.put("reporterReceivedAt", at.plusSeconds(1).toString());
        JsonNode engine = third.get("inference").get("engines").get(0);
        for (JsonNode counter : engine.get("counters")) if ("vllm:prompt_tokens_total".equals(counter.get("name").asText())) ((ObjectNode) counter).put("value", 400);
        ObjectNode histogram = (ObjectNode) engine.get("histograms").get(0);
        histogram.set("counts", json.valueToTree(List.of(4, 10, 10))); histogram.put("count", 10); histogram.put("sum", 1.65);
        send(json.writeValueAsString(third)).andExpect(status().isOk());
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", clock.at.toString(), "&bucketSeconds=300").get("telemetry").get("device");
        assertThat(metricMean(device, "rates", "vllm:prompt_tokens_total")).isCloseTo(300.0 / 180, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metricMean(device, "histogramMeans", "vllm:time_to_first_token_seconds")).isCloseTo(1.45 / 8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void changedCreationEpochProcessOrGapNeverProducesACrossBoundaryRate() throws Exception {
        JsonNode fixture = fixture("inference-observation-pipeline.json"); clock.at = NOW.plusSeconds(180);
        for (String reason : List.of("creation", "process", "gap")) {
            jdbc.update("delete from inference_observation_samples");
            send(fixture.get("requestBodies").get(0).asText()).andExpect(status().isOk());
            ObjectNode next = (ObjectNode) json.readTree(fixture.get("requestBodies").get(1).asText());
            if ("creation".equals(reason)) ((ObjectNode) next.get("inference").get("engines").get(0).get("histograms").get(0)).put("createdAt", NOW.getEpochSecond());
            if ("process".equals(reason)) ((ObjectNode) next.get("inference").get("identity")).put("processId", UUID.randomUUID().toString());
            if ("gap".equals(reason)) { Instant at = Instant.parse("2026-10-26T00:04:00.125Z"); setObserved(next, at); next.put("reporterReceivedAt", at.plusSeconds(1).toString()); }
            send(json.writeValueAsString(next)).andExpect(status().isOk());
            JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", clock.at.toString(), "&bucketSeconds=300").get("telemetry").get("device");
            String category = "creation".equals(reason) ? "histogramMeans" : "rates";
            String metric = "creation".equals(reason) ? "vllm:time_to_first_token_seconds" : "vllm:prompt_tokens_total";
            assertThat(hasMean(device, category, metric)).isFalse();
            assertThat(device.get("evidence").get("gap".equals(reason) ? "gapIntervals" : "resetIntervals").asLong()).isPositive();
        }
    }

    @Test
    void explicitGaugeTimeCanHaveARealZeroInABucketWithoutSnapshotRows() throws Exception {
        ObjectNode value = (ObjectNode) json.readTree(fixture("inference-observation-pipeline.json").get("requestBodies").get(1).asText());
        for (JsonNode gauge : value.get("inference").get("engines").get(0).get("gauges")) if ("vllm:num_requests_running".equals(gauge.get("name").asText())) {
            ((ObjectNode) gauge).put("sampleAt", 1792972800.125); ((ObjectNode) gauge).put("value", 0);
        }
        send(json.writeValueAsString(value)).andExpect(status().isOk());
        JsonNode point = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device").get("series").get(0);
        assertThat(point.get("samples").asLong()).isZero();
        JsonNode metric = point.get("engines").get(0).get("gauges").get("vllm:num_requests_running");
        assertThat(metric.get("mean").asDouble()).isZero(); assertThat(metric.get("observedSamples").asLong()).isEqualTo(1);
    }

    @Test
    void partialRetentionAndStaleSourceDoNotTurnHistoricalDataIntoFailure() throws Exception {
        Instant cutoff = NOW.minusSeconds(90L * 86400); ObjectNode sample = (ObjectNode) json.readTree(sample(UUID.randomUUID(), 1));
        setObserved(sample, cutoff.plusSeconds(30)); send(json.writeValueAsString(sample)).andExpect(status().isOk());
        JsonNode device = capacity(UserRole.SYS_VIEWER, cutoff.minusSeconds(60).toString(), cutoff.plusSeconds(60).toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        assertThat(device.get("evidence").get("retentionState").asText()).isEqualTo("PARTIAL");
        assertThat(device.get("evidence").get("freshness").asText()).isEqualTo("STALE");
        assertThat(device.get("series").get(0).get("samples").isNull()).isTrue();
        assertThat(device.get("series").get(1).get("samples").asLong()).isEqualTo(1);
    }

    @Test
    void boundedHistorySignalsTruncationAndKeepsTheSerializedDeviceSmall() throws Exception {
        ObjectNode template = (ObjectNode) json.readTree(fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText());
        ObjectNode inference = (ObjectNode) template.get("inference");
        ObjectNode identity = (ObjectNode) inference.get("identity");
        identity.put("bootId", "b".repeat(256)); identity.put("processId", "p".repeat(256)); identity.put("configId", "c".repeat(256));
        JsonNode original = inference.get("engines").get(0); ArrayNode engines = json.createArrayNode();
        for (int i = 0; i < 32; i++) {
            ObjectNode engine = (ObjectNode) original.deepCopy(); engine.put("modelName", "m".repeat(250) + i); engine.put("engineId", "e".repeat(256)); engines.add(engine);
        }
        inference.set("engines", engines);
        for (int i = 0; i < 90; i++) {
            Instant at = NOW.minusSeconds((90L - i) * 60); template.put("sequence", i + 1); setObserved(template, at);
            template.put("reporterReceivedAt", at.plusSeconds(1).toString()); send(json.writeValueAsString(template)).andExpect(status().isOk());
        }
        JsonNode device = capacity(UserRole.SYS_VIEWER, NOW.minusSeconds(90L * 60).toString(), NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        assertThat(device.get("evidence").get("truncated").asBoolean()).isTrue();
        assertThat(json.writeValueAsBytes(device).length).isLessThanOrEqualTo(ObservationLimits.MAX_RESPONSE_BYTES);
        assertThat(device.get("series").size()).isLessThanOrEqualTo(1440);
    }

    @Test
    void anotherLogicalIdentityCannotCreateASecondSampleInTheSameMinute() throws Exception {
        send(sample(UUID.randomUUID(), 1)).andExpect(status().isOk());
        send(sample(UUID.randomUUID(), 2)).andExpect(status().isConflict());
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void sourceBindingAndCredentialNamespacesRemainIndependent() throws Exception {
        String body = sample(UUID.randomUUID(), 1);
        String wrongSource = body.replace(SOURCE.toString(), UUID.randomUUID().toString());
        send(wrongSource).andExpect(status().isForbidden());
        for (String token : List.of("synthetic-gateway-token", "synthetic-ssh-token", jwt.createAccessToken(user(UserRole.SYS_ADMIN)))) {
            mvc.perform(post("/internal/inference-observations").with(r -> { r.setRemoteAddr(PEER); return r; })
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/internal/llm/sync").with(r -> { r.setRemoteAddr(PEER); return r; })
                .header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        assertThat(rows()).isZero();
    }

    @Test
    void unknownMethodsPathsAndOversizedBodiesAreRefused() throws Exception {
        mvc.perform(get("/internal/inference-observations").with(r -> { r.setRemoteAddr(PEER); return r; })
                .header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/inference-observations/unknown").with(r -> { r.setRemoteAddr(PEER); return r; })
                .header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        send(" ".repeat(ObservationLimits.MAX_BODY_BYTES + 1)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rows()).isZero();
    }

    @Test
    void rejectsUnsafeSequenceClockAndNestedDimensionsWithoutStoringThem() throws Exception {
        String body = fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText();
        List<ObjectNode> invalid = new ArrayList<>();
        ObjectNode sequence = (ObjectNode) json.readTree(body); sequence.put("sequence", ObservationLimits.MAX_SAFE_INTEGER + 1); invalid.add(sequence);
        ObjectNode extra = (ObjectNode) json.readTree(body); extra.put("rawMetrics", "unapproved arbitrary text"); invalid.add(extra);
        ObjectNode labels = (ObjectNode) json.readTree(body); ((ObjectNode) labels.get("inference").get("engines").get(0).get("counters").get(0).get("labels")).put("user", "synthetic-untrusted-label"); invalid.add(labels);
        ObjectNode number = (ObjectNode) json.readTree(body); ((ObjectNode) number.get("host").get("metrics").get("cpu_load1")).put("value", "NaN"); invalid.add(number);
        ObjectNode buckets = (ObjectNode) json.readTree(body); ((ArrayNode) buckets.get("inference").get("engines").get(0).get("histograms").get(0).get("counts")).set(0, json.valueToTree(3)); invalid.add(buckets);
        ObjectNode duplicate = (ObjectNode) json.readTree(body); ArrayNode metrics = (ArrayNode) duplicate.get("inference").get("engines").get(0).get("counters"); metrics.add(metrics.get(0).deepCopy()); invalid.add(duplicate);
        for (ObjectNode sample : invalid) send(json.writeValueAsString(sample)).andExpect(status().isUnprocessableContent());
        send(body.replace("\"version\":1", "\"version\":1,\"version\":1")).andExpect(status().isUnprocessableContent());
        assertThat(rows()).isZero();
    }

    @Test
    void immediatelyExpiresOldReadDataAndPurgesOnlyTheNewTable() throws Exception {
        send(sample(UUID.randomUUID(), 1)).andExpect(status().isOk());
        String event = "synthetic-observation-" + UUID.randomUUID();
        jdbc.update("insert into llm_usage_events(event_id,status,requested_at) values (?,'OK',?)", event, Timestamp.from(NOW.minusSeconds(120L * 86400)));
        clock.at = NOW.plusSeconds(90L * 86400 + 1);
        JsonNode response = capacity(UserRole.SYS_VIEWER, NOW.minusSeconds(240).toString(), NOW.minusSeconds(60).toString(), "");
        JsonNode evidence = response.get("telemetry").get("device").get("evidence");
        assertThat(evidence.get("retentionState").asText()).isEqualTo("OUTSIDE");
        assertThat(evidence.get("samplesInWindow").isNull()).isTrue();
        assertThat(rows()).isEqualTo(1);
        retention.sweep();
        assertThat(rows()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from llm_usage_events where event_id=?", Long.class, event)).isEqualTo(1);
    }

    @Test
    void invalidReplayAndFutureClocksAreRejectedBeforeAnyInsert() throws Exception {
        ObjectNode old = (ObjectNode) json.readTree(sample(UUID.randomUUID(), 1));
        setObserved(old, NOW.minusSeconds(90L * 86400 + 1));
        send(json.writeValueAsString(old)).andExpect(status().isUnprocessableContent());
        ObjectNode future = (ObjectNode) json.readTree(sample(UUID.randomUUID(), 2));
        setObserved(future, NOW.plusSeconds(ObservationLimits.FUTURE_SKEW_SECONDS + 1));
        send(json.writeValueAsString(future)).andExpect(status().isUnprocessableContent());
        assertThat(rows()).isZero();
    }

    @Test
    void historicalSourceBindingSurvivesRegistryDeletion() throws Exception {
        send(sample(UUID.randomUUID(), 1)).andExpect(status().isOk());
        jdbc.update("delete from llm_upstreams where public_id=?", UPSTREAM);
        JsonNode response = capacity(UserRole.SYS_VIEWER, NOW.minusSeconds(240).toString(), NOW.toString(), "");
        assertThat(response.get("telemetry").get("device").get("upstreamId").asText()).isEqualTo(UPSTREAM.toString());
        assertThat(response.get("telemetry").get("device").get("upstreamName").asText()).isEqualTo("Synthetic observation source");
    }

    @Test
    void globalDeviceWindowDoesNotInheritInstitutionModelOrReasonFilters() throws Exception {
        for (JsonNode body : fixture("inference-observation-pipeline.json").get("requestBodies")) send(body.asText()).andExpect(status().isOk());
        Org other = orgs.save(new Org("Synthetic demand filter " + UUID.randomUUID(), null));
        JsonNode result = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", NOW.toString(),
                "&orgId=" + other.getPublicId() + "&publicModelName=other-public-alias&errorType=quota_exhausted");
        assertThat(result.get("summary").get("requests").asLong()).isZero();
        assertThat(result.get("telemetry").get("device").get("evidence").get("samplesInWindow").asLong()).isEqualTo(2);
    }

    @Test
    void institutionReadersNeverReceiveDeviceLabelsOrSourceExistence() throws Exception {
        for (JsonNode body : fixture("inference-observation-pipeline.json").get("requestBodies")) send(body.asText()).andExpect(status().isOk());
        User actor = user(UserRole.ORG_VIEWER); Org org = orgs.save(new Org("Synthetic observer " + UUID.randomUUID(), null));
        Workspace workspace = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Synthetic observer space", null));
        var assignment = InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(), Set.of(InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(workspace.getPublicId()))), 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))",
                actor.getId(), org.getId(), assignment.displayRole().name(), encoded.mode(), encoded.payload());
        for (UUID requested : List.of(SOURCE, UUID.randomUUID())) {
            JsonNode result = json.readTree(mvc.perform(get("/api/v1/admin/llm/capacity")
                            .param("from", "2026-10-26T00:00:00.125Z").param("to", NOW.toString())
                            .param("orgId", org.getPublicId().toString()).param("telemetrySourceId", requested.toString())
                            .header("Authorization", "Bearer " + jwt.createAccessToken(actor)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(result.get("telemetryVisible").asBoolean()).isFalse();
            assertThat(result.get("telemetry").get("status").asText()).isEqualTo("NOT_PROVIDED");
            assertThat(result.get("telemetry").get("device").isNull()).isTrue();
        }
    }

    @Test
    void completeIntervalsBelongOnlyToTheirEndingBucket() throws Exception {
        for (JsonNode body : fixture("inference-observation-pipeline.json").get("requestBodies")) send(body.asText()).andExpect(status().isOk());
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-25T23:59:30.125Z", NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        long observed = 0;
        for (JsonNode point : device.get("series")) for (JsonNode engine : point.get("engines")) {
            JsonNode metric = engine.get("histogramMeans").get("vllm:time_to_first_token_seconds");
            if (metric != null && !metric.get("mean").isNull()) observed++;
        }
        assertThat(observed).isEqualTo(1);
        assertThat(device.get("series").get(0).get("engines").get(0).get("histogramMeans")
                .get("vllm:time_to_first_token_seconds").get("mean").isNull()).isTrue();
    }

    @Test
    void missingCounterSeriesDowngradesTheRemainingEngine() throws Exception {
        JsonNode fixture = fixture("inference-observation-pipeline.json");
        send(fixture.get("requestBodies").get(0).asText()).andExpect(status().isOk());
        ObjectNode next = (ObjectNode) json.readTree(fixture.get("requestBodies").get(1).asText());
        JsonNode group = next.get("inference").get("engines").get(0);
        ((ArrayNode) group.get("counters")).remove(1);
        ((ArrayNode) group.get("notReportedMetricNames")).add("vllm:prompt_tokens_cached_total");
        ((ArrayNode) next.get("inference").get("notReportedMetricNames")).add("vllm:prompt_tokens_cached_total");
        send(json.writeValueAsString(next)).andExpect(status().isOk());
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-25T23:59:30.125Z", NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        assertThat(device.get("series").get(1).get("engines").get(0).get("intervalState").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void explicitGaugeTimestampsOutsideTheWindowRemainUnknown() throws Exception {
        ObjectNode sample = (ObjectNode) json.readTree(fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText());
        JsonNode gauges = sample.get("inference").get("engines").get(0).get("gauges");
        for (JsonNode gauge : gauges) if ("vllm:num_requests_running".equals(gauge.get("name").asText())) {
            ((ObjectNode) gauge).put("sampleAt", NOW.plusSeconds(3600).getEpochSecond());
            ((ObjectNode) gauge).put("value", 7);
        }
        send(json.writeValueAsString(sample)).andExpect(status().isOk());
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        assertThat(device.get("series").get(0).get("engines").get(0).get("gauges")
                .get("vllm:num_requests_running").get("mean").isNull()).isTrue();
    }

    @Test
    void impossibleHistogramDeltaIsNotAnObservedIntervalMean() throws Exception {
        JsonNode fixture = fixture("inference-observation-pipeline.json");
        for (int i = 0; i < 2; i++) {
            ObjectNode sample = (ObjectNode) json.readTree(fixture.get("requestBodies").get(i).asText());
            ObjectNode histogram = (ObjectNode) sample.get("inference").get("engines").get(0).get("histograms").get(0);
            histogram.set("bounds", json.valueToTree(List.of(1, "+Inf")));
            histogram.set("counts", json.valueToTree(i == 0 ? List.of(1, 2) : List.of(2, 3)));
            histogram.put("count", i == 0 ? 2 : 3); histogram.put("sum", i == 0 ? 10 : 12);
            send(json.writeValueAsString(sample)).andExpect(status().isOk());
        }
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", NOW.toString(), "&bucketSeconds=60")
                .get("telemetry").get("device");
        assertThat(device.get("series").get(1).get("engines").get(0).get("histogramMeans")
                .get("vllm:time_to_first_token_seconds").get("mean").isNull()).isTrue();
    }

    @Test
    void ingressPreservesProducerCountRatioStateAndHostPercentDomains() throws Exception {
        String body = fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText();
        List<ObjectNode> invalid = new ArrayList<>();
        ObjectNode counter = (ObjectNode) json.readTree(body);
        ((ObjectNode) counter.get("inference").get("engines").get(0).get("counters").get(0)).put("value", .5); invalid.add(counter);
        ObjectNode requests = (ObjectNode) json.readTree(body);
        ((ObjectNode) requests.get("inference").get("engines").get(0).get("gauges").get(1)).put("value", .5); invalid.add(requests);
        ObjectNode ratio = (ObjectNode) json.readTree(body);
        ((ObjectNode) ratio.get("inference").get("engines").get(0).get("gauges").get(0)).put("value", 1.5); invalid.add(ratio);
        ObjectNode state = (ObjectNode) json.readTree(body);
        ObjectNode gauge = (ObjectNode) state.get("inference").get("engines").get(0).get("gauges").get(0);
        gauge.put("name", "vllm:engine_sleep_state"); gauge.put("unit", "state"); gauge.put("value", 2); invalid.add(state);
        ObjectNode percent = (ObjectNode) json.readTree(body);
        ((ObjectNode) percent.get("host").get("metrics").get("gpu_utilization_percent")).put("value", 101); invalid.add(percent);
        List<Integer> statuses = new ArrayList<>();
        for (ObjectNode sample : invalid) {
            statuses.add(send(json.writeValueAsString(sample)).andReturn().getResponse().getStatus());
            jdbc.update("delete from inference_observation_samples");
        }
        assertThat(statuses).containsExactly(422, 422, 422, 422, 422);
    }

    @Test
    void subMicrosecondInputCannotCrossTheDatabaseMinuteBoundary() throws Exception {
        ObjectNode sample = (ObjectNode) json.readTree(sample(UUID.randomUUID(), 1));
        setObserved(sample, Instant.parse("2026-10-26T00:00:59.9999999Z"));
        send(json.writeValueAsString(sample)).andExpect(status().isUnprocessableContent());
        assertThat(rows()).isZero();
    }

    @Test
    void anEngineThatDisappearsDowngradesEarlierValidIntervalsInTheSameBucket() throws Exception {
        JsonNode fixture = fixture("inference-observation-pipeline.json");
        clock.at = NOW.plusSeconds(120);
        for (int i = 0; i < 4; i++) {
            ObjectNode value = (ObjectNode) json.readTree(fixture.get("requestBodies").get(Math.min(i, 1)).asText());
            Instant at = Instant.parse("2026-10-26T00:00:00.125Z").plusSeconds(i * 60L);
            value.put("sequence", i + 1); setObserved(value, at); value.put("reporterReceivedAt", at.plusSeconds(1).toString());
            if (i == 3) ((ObjectNode) value.get("inference")).set("engines", json.createArrayNode());
            send(json.writeValueAsString(value)).andExpect(status().isOk());
        }
        JsonNode device = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.125Z", clock.at.toString(), "&bucketSeconds=120")
                .get("telemetry").get("device");
        assertThat(device.get("series").get(1).get("engines").get(0).get("intervalState").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void boundedSecondaryLabelsPreserveEmptyAndEscapedNewlineButRejectNul() throws Exception {
        String body = fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText();
        List<Integer> statuses = new ArrayList<>();
        for (String label : List.of("", "line\nline", "line" + (char) 0 + "line")) {
            ObjectNode sample = (ObjectNode) json.readTree(body);
            JsonNode counters = sample.get("inference").get("engines").get(0).get("counters");
            for (JsonNode counter : counters) if ("vllm:request_success_total".equals(counter.get("name").asText())) {
                ((ObjectNode) counter.get("labels")).put("finished_reason", label);
            }
            int status = send(json.writeValueAsString(sample)).andReturn().getResponse().getStatus(); statuses.add(status);
            if (status == 200) {
                JsonNode stored = json.readTree(jdbc.queryForObject("select inference::text from inference_observation_samples", String.class));
                assertThat(stored.get("engines").get(0).get("counters").get(3).get("labels").get("finished_reason").asText()).isEqualTo(label);
            }
            jdbc.update("delete from inference_observation_samples");
        }
        assertThat(statuses).containsExactly(200, 200, 422);
    }

    @Test
    void exactSubMicrosecondLowerBoundExcludesBothStoredPopulations() throws Exception {
        String model = boundaryRows();
        JsonNode response = capacity(UserRole.SYS_VIEWER, "2026-10-26T00:00:00.1250001Z", NOW.toString(), "&publicModelName=" + model);
        assertThat(List.of(response.get("summary").get("requests").asLong(),
                response.get("telemetry").get("device").get("evidence").get("samplesInWindow").asLong())).containsExactly(0L, 0L);
    }

    @Test
    void exactSubMicrosecondUpperBoundIncludesBothStoredPopulations() throws Exception {
        String model = boundaryRows();
        JsonNode response = capacity(UserRole.SYS_VIEWER, "2026-10-25T23:59:00.125Z", "2026-10-26T00:00:00.1250001Z", "&publicModelName=" + model);
        assertThat(List.of(response.get("summary").get("requests").asLong(),
                response.get("telemetry").get("device").get("evidence").get("samplesInWindow").asLong())).containsExactly(1L, 1L);
    }

    private String boundaryRows() throws Exception {
        send(fixture("inference-observation-pipeline.json").get("requestBodies").get(0).asText()).andExpect(status().isOk());
        String model = "synthetic-boundary-" + UUID.randomUUID();
        jdbc.update("insert into llm_usage_events(event_id,public_model_name,status,requested_at) values (?,?,'OK',?)",
                UUID.randomUUID().toString(), model, Timestamp.from(Instant.parse("2026-10-26T00:00:00.125Z")));
        return model;
    }

    private JsonNode fixture(String resource) throws Exception {
        try (var stream = getClass().getResourceAsStream("/observation/" + resource)) { return json.readTree(stream); }
    }
    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/internal/inference-observations").with(request -> { request.setRemoteAddr(PEER); return request; })
                .header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)));
    }
    private long rows() { return jdbc.queryForObject("select count(*) from inference_observation_samples", Long.class); }
    private User user(UserRole role) {
        User value = new User("observation-" + UUID.randomUUID() + "@example.invalid", "{test-no-login}", "관측 시험");
        value.setRole(role); value.setStatus(UserStatus.ACTIVE); value.setEmailVerifiedAt(Instant.now()); return users.save(value);
    }
    private JsonNode capacity(UserRole role, String from, String to, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/admin/llm/capacity?from=" + from + "&to=" + to + query)
                .header("Authorization", "Bearer " + jwt.createAccessToken(user(role))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private static String digest(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static void setObserved(ObjectNode value, Instant at) {
        value.put("observedAt", at.toString()); ((ObjectNode) value.get("inference")).put("observedAt", at.getEpochSecond() + at.getNano() / 1e9);
    }
    private static double metricMean(JsonNode device, String category, String key) {
        for (JsonNode point : device.get("series")) for (JsonNode engine : point.get("engines")) {
            JsonNode metric = engine.get(category).get(key);
            if (metric != null && !metric.get("mean").isNull()) return metric.get("mean").asDouble();
        }
        throw new AssertionError("Expected an observed metric");
    }
    private static boolean hasMean(JsonNode device, String category, String key) {
        for (JsonNode point : device.get("series")) for (JsonNode engine : point.get("engines")) {
            JsonNode metric = engine.get(category).get(key); if (metric != null && !metric.get("mean").isNull()) return true;
        }
        return false;
    }

    static String sample(UUID epoch, long sequence) {
        Instant observed = NOW.minusSeconds(180);
        return """
                {"version":1,"sourceId":"%s","epoch":"%s","sequence":%d,
                 "observedAt":"%s","reporterReceivedAt":"%s",
                 "host":{"state":"OBSERVED","metrics":{
                   "memory_total_bytes":{"state":"OBSERVED","value":128000000000},
                   "memory_available_bytes":{"state":"OBSERVED","value":24000000000}}},
                 "inference":{"version":1,"observedAt":%s,"sourceState":"NOT_REPORTED",
                   "identity":{"sourceId":"%s","bootId":null,"processId":null,"configId":null},
                   "engines":[],"unsupportedMetricNames":[],"notReportedMetricNames":[]}}
                """.formatted(SOURCE, epoch, sequence, observed, NOW.minusSeconds(60),
                        observed.getEpochSecond() + observed.getNano() / 1_000_000_000.0, SOURCE);
    }
}
