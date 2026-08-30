package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Role scope, diagnostic redaction and metric semantics for admin LLM observability. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmObservabilityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Org orgA;
    private Org orgB;
    private long workspaceA;
    private long workspaceB;
    private User requester;
    private String ownedRef;
    private String otherRef;
    private String unknownRef;
    private String restrictedRef;
    private String ownedName;
    private String otherName;
    private String restrictedName;
    private String orgViewerToken;
    private String sysViewerToken;
    private final List<String> allAdminTokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        orgA = org("LLM 관측 기관 A " + suffix);
        orgB = org("LLM 관측 기관 B " + suffix);
        workspaceA = workspace("LLM 관측 A " + suffix);
        workspaceB = workspace("LLM 관측 B " + suffix);
        requester = user("llm-observe-requester-" + suffix + "@pusan.ac.kr", "관측 신청자",
                UserRole.USER, null);
        ownedRef = "observe-a-" + suffix;
        otherRef = "observe-b-" + suffix;
        unknownRef = "observe-unknown-" + suffix;
        restrictedRef = "observe-restricted-" + suffix;
        ownedName = "기관 A upstream " + suffix;
        otherName = "기관 B upstream " + suffix;
        restrictedName = "제한 모델 전용 upstream " + suffix;
        insertUpstream(ownedRef, ownedName, orgA.getId());
        insertUpstream(otherRef, otherName, orgB.getId());
        jdbcTemplate.update("""
                insert into llm_upstreams (ref, kind, display_name, dedicated, enabled,
                                           passthrough)
                values (?, 'EXTERNAL_API', ?, false, true, false)
                """, restrictedRef, restrictedName);
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model,
                                        visibility, enabled)
                values (?, ?, 'restricted-upstream-model', 'RESTRICTED', true)
                """, "restricted-model-" + suffix, restrictedRef);

        orgViewerToken = token(user("llm-observe-org-viewer-" + suffix + "@pusan.ac.kr",
                "기관 열람자", UserRole.ORG_VIEWER, orgA.getId()));
        allAdminTokens.clear();
        allAdminTokens.add(orgViewerToken);
        allAdminTokens.add(token(user("llm-observe-org-manager-" + suffix + "@pusan.ac.kr",
                "기관 운영자", UserRole.ORG_MANAGER, orgA.getId())));
        allAdminTokens.add(token(user("llm-observe-org-admin-" + suffix + "@pusan.ac.kr",
                "기관 관리자", UserRole.ORG_ADMIN, orgA.getId())));
        sysViewerToken = token(user("llm-observe-sys-viewer-" + suffix + "@pusan.ac.kr",
                "시스템 열람자", UserRole.SYS_VIEWER, null));
        allAdminTokens.add(sysViewerToken);
        allAdminTokens.add(token(user("llm-observe-sys-manager-" + suffix + "@pusan.ac.kr",
                "시스템 운영자", UserRole.SYS_MANAGER, null)));
        allAdminTokens.add(token(user("llm-observe-sys-admin-" + suffix + "@pusan.ac.kr",
                "시스템 관리자", UserRole.SYS_ADMIN, null)));

        reportGatewayAndUpstreams();
        long historicalKey = key(orgA.getId(), workspaceA, "오래된 공유 경로 키");
        eventAt(historicalKey, restrictedRef, 1, "OK", null, 1, 1, false, 10,
                Instant.now().minus(32, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    void upstreamRefsAreCaseInsensitiveUnique() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into llm_upstreams (ref, kind, display_name, dedicated, enabled,
                                           passthrough)
                values (?, 'ON_PREM', '대소문자 중복', false, true, false)
                """, ownedRef.toUpperCase(java.util.Locale.ROOT)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void sixAdminRolesReadWhileUserIsDeniedAndOrgDiagnosticsAreRedacted() throws Exception {
        for (String token : allAdminTokens) {
            mockMvc.perform(get("/api/v1/admin/llm/status")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/llm/metrics")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + token(requester)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/llm/status?orgId=" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams.length()").value(0));

        String orgBody = mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.lastContactAt").isNotEmpty())
                .andExpect(jsonPath("$.gateway.usageQueueReportState").value("FRESH"))
                .andExpect(jsonPath("$.gateway.agentVersion").value((Object) null))
                .andReturn().getResponse().getContentAsString();
        JsonNode orgJson = objectMapper.readTree(orgBody);
        JsonNode owned = findUpstream(orgJson, ownedName);
        assertThat(owned).isNotNull();
        assertThat(owned.get("ref").isNull()).isTrue();
        assertThat(owned.get("orgId").isNull()).isTrue();
        // Repeated request-specific rejections prove reachability; they are
        // not an upstream outage even with a cooldown timestamp present.
        assertThat(owned.get("availability").asString()).isEqualTo("HEALTHY");
        assertThat(owned.get("active").get("stale").asBoolean()).isFalse();
        assertThat(owned.get("catalog").get("missingModelCount").asInt()).isZero();
        assertThat(owned.get("catalog").get("unexpectedModelCount").asInt()).isZero();
        JsonNode openrouter = findUpstream(orgJson, "OpenRouter");
        assertThat(openrouter).isNotNull();
        assertThat(openrouter.get("active").get("intervalSeconds").asInt()).isEqualTo(300);
        assertThat(openrouter.get("catalog").get("unexpectedModelCount").isNull()).isTrue();
        assertThat(orgBody).doesNotContain(otherRef).doesNotContain(unknownRef)
                .doesNotContain("restricted-internal-model").doesNotContain(restrictedName);

        jdbcTemplate.update("update llm_upstream_state set passive_last_failure_type = "
                + "'KEY_THROTTLED', passive_consecutive_failures = 12 where ref = ?", ownedRef);
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[?(@.name == '%s')].availability"
                        .formatted(ownedName)).value("HEALTHY"));

        jdbcTemplate.update("""
                update llm_upstream_state
                   set passive_last_failure_type = 'UPSTREAM_ERROR',
                       passive_consecutive_failures = 3,
                       passive_last_failure_at = now(),
                       passive_cooldown_until = now() + interval '5 minutes',
                       active_status = 'OK', active_last_attempt_at = now(),
                       active_last_success_at = now(), active_probe_interval_seconds = 60
                 where ref = ?
                """, ownedRef);
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[?(@.name == '%s')].availability"
                        .formatted(ownedName)).value("UNAVAILABLE"));

        jdbcTemplate.update("""
                update llm_upstream_state
                   set passive_last_attempt_at = now(),
                       passive_last_success_at = now() - interval '1 day',
                       passive_last_failure_at = null,
                       passive_cooldown_until = null, active_status = 'OK',
                       active_last_attempt_at = now() - interval '4 minutes',
                       active_last_success_at = now() - interval '4 minutes',
                       active_probe_interval_seconds = 60, catalog_status = 'MISMATCH'
                 where ref = ?
                """, ownedRef);
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[?(@.name == '%s')].availability"
                        .formatted(ownedName)).value("UNKNOWN"))
                .andExpect(jsonPath("$.upstreams[?(@.name == '%s')].active.stale"
                        .formatted(ownedName)).value(true));

        String sysBody = mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.agentVersion").value("gateway-test"))
                .andExpect(jsonPath("$.gateway.usageQueueScanFailures").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(sysBody).contains(ownedRef).contains(otherRef).contains(unknownRef)
                .contains("restricted-internal-model").contains(restrictedRef);
        JsonNode unknown = findUpstreamByRef(objectMapper.readTree(sysBody), unknownRef);
        assertThat(unknown.get("enabled").isNull()).isTrue();
        assertThat(unknown.get("dedicated").isNull()).isTrue();
    }

    @Test
    void staleGatewayMakesEvenUnregisteredObservationAvailabilityUnknown() throws Exception {
        jdbcTemplate.update("update llm_gateway_state set last_contact_at = now() - interval '1 minute'");

        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.reportState").value("STALE"))
                .andExpect(jsonPath("$.upstreams[?(@.ref == '%s')].reportState"
                        .formatted(unknownRef)).value("UNREGISTERED"))
                .andExpect(jsonPath("$.upstreams[?(@.ref == '%s')].availability"
                        .formatted(unknownRef)).value("UNKNOWN"));

        jdbcTemplate.update("update llm_gateway_state set usage_queue_observed_at = "
                + "now() - interval '11 minutes'");
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.usageQueueReportState").value("STALE"));

        jdbcTemplate.update("update llm_gateway_state set usage_queue_observed_at = null");
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.usageQueueReportState")
                        .value("NOT_REPORTED"));

        jdbcTemplate.update("update llm_gateway_state set last_contact_at = now(), "
                + "upstream_observation_format = null");
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[?(@.ref == '%s')].availability"
                        .formatted(unknownRef)).value("UNKNOWN"));

        jdbcTemplate.update("update llm_gateway_state set upstream_observation_format = 2");
        mockMvc.perform(get("/api/v1/admin/llm/status")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[?(@.ref == '%s')].reportState"
                        .formatted(ownedRef)).value("NOT_REPORTED"))
                .andExpect(jsonPath("$.upstreams[?(@.ref == '%s')].availability"
                        .formatted(ownedRef)).value("UNKNOWN"));
    }

    @Test
    void metricsUseFinalOutcomeSemanticsAndExcludeHistoricalAttributionGaps() throws Exception {
        long keyA = key(orgA.getId(), workspaceA, "관측 A 키");
        long keyB = key(orgB.getId(), workspaceB, "관측 B 키");
        event(keyA, "openrouter", 1, "OK", null, 10, 20, false, 100);
        event(keyA, "openrouter", 2, "TIMEOUT", "upstream_timeout", 0, 0, true, 500);
        event(keyA, null, null, "RATE_LIMITED", "rate_limit_requests", 0, 0, false, 2);
        // This is indistinguishable from the known pre-fix attribution gap;
        // it affects coverage but must not be mislabeled a local rejection.
        event(keyA, null, null, "UPSTREAM_ERROR", "upstream_error", 0, 0, false, 300);
        // Misrouted/historical attribution must affect coverage without
        // revealing another organisation's dedicated upstream identity.
        event(keyA, otherRef, 1, "OK", null, 3, 4, false, 80);
        event(keyB, "openrouter", 1, "OK", null, 100, 200, false, 90);

        String orgMetrics = mockMvc.perform(
                        get("/api/v1/admin/llm/metrics?orgId=" + orgA.getPublicId())
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(5))
                .andExpect(jsonPath("$.attributedEvents").value(3))
                .andExpect(jsonPath("$.attributionCoverage").value(0.6))
                .andExpect(jsonPath("$.attemptCoverage").value(0.6))
                .andExpect(jsonPath("$.estimatedCoverage").value(0.2))
                .andExpect(jsonPath("$.upstreams.length()").value(1))
                .andExpect(jsonPath("$.upstreams[0].ref").value((Object) null))
                .andExpect(jsonPath("$.upstreams[0].finalOutcomes").value(2))
                .andExpect(jsonPath("$.upstreams[0].timeoutOrError").value(1))
                .andExpect(jsonPath("$.upstreams[0].timeoutOrErrorRate").value(0.5))
                .andExpect(jsonPath("$.upstreams[0].multiAttemptRate").value(0.5))
                .andExpect(jsonPath("$.upstreams[0].attemptAmplification").value(1.5))
                .andExpect(jsonPath("$.upstreams[0].latencySamples").value(1))
                .andExpect(jsonPath("$.upstreams[0].latencyP50Ms").value(100))
                .andExpect(jsonPath("$.localRejections.length()").value(1))
                .andExpect(jsonPath("$.localRejections[0].errorType")
                        .value("rate_limit_requests"))
                .andReturn().getResponse().getContentAsString();
        assertThat(orgMetrics).doesNotContain(otherRef).doesNotContain(otherName);

        mockMvc.perform(get("/api/v1/admin/llm/metrics?orgId=" + orgA.getPublicId())
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreams[0].ref").value("openrouter"))
                .andExpect(jsonPath("$.upstreams[1].ref").value(otherRef));

        mockMvc.perform(get("/api/v1/admin/llm/metrics?orgId=" + orgB.getPublicId())
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isNotFound());
    }

    private void reportGatewayAndUpstreams() {
        jdbcTemplate.update("""
                insert into llm_gateway_state (id, generation, applied_generation,
                    supported_format, agent_version, in_flight, last_contact_at,
                    upstream_observation_format, queued_usage_events, queued_usage_bytes,
                    usage_queue_observed_at, usage_queue_scan_failures)
                values (true, 8, 8, 1, 'gateway-test', 0, now(), 1, 0, 0, now(), 4)
                on conflict (id) do update set generation = 8, applied_generation = 8,
                    supported_format = 1, agent_version = 'gateway-test', in_flight = 0,
                    last_contact_at = now(), upstream_observation_format = 1,
                    queued_usage_events = 0, queued_usage_bytes = 0,
                    usage_queue_observed_at = now(), usage_queue_scan_failures = 4
                """);
        jdbcTemplate.update("delete from llm_upstream_state "
                + "where ref in (?, ?, ?, ?, 'openrouter')",
                ownedRef, otherRef, unknownRef, restrictedRef);
        jdbcTemplate.update("""
                insert into llm_upstream_state (ref, configured, last_reported_at,
                    passive_last_attempt_at, passive_last_failure_at,
                    passive_last_failure_type, passive_consecutive_failures,
                    passive_cooldown_until, active_status, catalog_status)
                values (?, true, now(), now(), now(), 'REQUEST_REJECTED', 9,
                    now() + interval '5 minutes', 'UNKNOWN', 'MATCH')
                """, ownedRef);
        jdbcTemplate.update("""
                insert into llm_upstream_state (ref, configured, last_reported_at,
                    active_last_attempt_at, active_last_success_at, active_status,
                    active_probe_interval_seconds, active_latency_ms, active_model_count,
                    active_consecutive_failures,
                    catalog_status, catalog_expected_model_count,
                    catalog_missing_model_count, catalog_unexpected_model_count,
                    catalog_missing_public_models)
                values (?, true, now(), now(), now(), 'OK', 60, 12, 1, 0, 'MATCH',
                        null, null, null, null),
                       (?, true, now(), now(), now(), 'OK', 300, 14, 1, 0, 'MATCH',
                        null, null, null, null),
                       (?, true, now(), now(), now(), 'OK', 300, 18, 1, 0, 'MATCH',
                        null, null, null, null),
                       ('openrouter', true, now(), now(), now(), 'OK', 300, 20, 100, 0,
                        'MISMATCH', 2, 1, null, '["restricted-internal-model"]')
                """, otherRef, unknownRef, restrictedRef);
    }

    private void insertUpstream(String ref, String name, long orgId) {
        jdbcTemplate.update("""
                insert into llm_upstreams (ref, kind, display_name, org_id, dedicated,
                                           enabled, passthrough)
                values (?, 'ON_PREM', ?, ?, true, true, false)
                """, ref, name, orgId);
    }

    private long key(long orgId, long workspaceId, String name) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '관측 테스트', ?)
                returning id
                """, Long.class, workspaceId, orgId, requester.getId(), name + " 신청");
        jdbcTemplate.update("insert into llm_key_request_details (request_id) values (?)", requestId);
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                    token_hash, token_prefix, status, created_by)
                values (?, ?, ?, ?, '관측 테스트', ?, 'pickle-test', 'ACTIVE', ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, hash, requester.getId());
    }

    private void event(long keyId, String ref, Integer attempts, String eventStatus,
            String errorType, int input, int output, boolean estimated, long latency) {
        eventAt(keyId, ref, attempts, eventStatus, errorType, input, output, estimated, latency,
                Instant.now().minusSeconds(60));
    }

    private void eventAt(long keyId, String ref, Integer attempts, String eventStatus,
            String errorType, int input, int output, boolean estimated, long latency,
            Instant requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name,
                    upstream_ref, attempts, status, error_type, input_tokens, output_tokens,
                    estimated, latency_ms, requested_at)
                values (?, ?, 'model', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), keyId, ref, attempts, eventStatus,
                errorType, input, output, estimated, latency,
                OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC));
    }

    private JsonNode findUpstream(JsonNode response, String name) {
        for (JsonNode node : response.get("upstreams")) {
            if (name.equals(node.get("name").asString())) {
                return node;
            }
        }
        return null;
    }

    private JsonNode findUpstreamByRef(JsonNode response, String ref) {
        for (JsonNode node : response.get("upstreams")) {
            if (ref.equals(node.get("ref").asString())) {
                return node;
            }
        }
        return null;
    }

    private long workspace(String name) {
        return workspaceRepository.save(new Workspace(WorkspaceKind.TEAM, name, null)).getId();
    }

    private Org org(String name) {
        return orgRepository.save(new Org(name, null));
    }

    private User user(String email, String name, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", name);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(role);
        User saved = userRepository.save(user);
        SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        return saved;
    }

    private String token(User user) {
        return jwtService.createAccessToken(user);
    }
}
