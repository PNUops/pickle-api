package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic raw events exercise actual HTTP scope, windows and projections. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmCapacityTest {
    private static final Instant FROM = Instant.parse("2021-02-03T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(3600);
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;
    @Autowired private WorkspaceRepository workspaces;
    private Org first;
    private Org second;
    private Workspace one;
    private Workspace two;
    private User owner;

    @BeforeEach
    void syntheticRows() {
        String suffix = UUID.randomUUID().toString();
        first = orgs.save(new Org("Capacity first " + suffix, null));
        second = orgs.save(new Org("Capacity second " + suffix, null));
        one = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Capacity one " + suffix, null));
        two = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Capacity two " + suffix, null));
        owner = user(UserRole.USER);
        jdbc.update("delete from llm_usage_rollup_state");
    }

    @Test
    void oneAbsoluteWindowPartitionsCurrentReasonsWithoutGuessingAnAxis() throws Exception {
        UUID key = key(first, one, "window key");
        event(key, FROM, null, null, "RATE_LIMITED", "large_request_busy", 0, null);
        event(key, FROM.plusSeconds(1), null, null, "AUTH_REJECTED", "endpoint_not_allowed", 0, null);
        event(key, FROM.plusSeconds(2), "TOKEN", null, "RATE_LIMITED", "server_busy", 0, null);
        attempts(key, 0);
        event(key, FROM.plusSeconds(3), "TOKEN", "capacity-external", "UPSTREAM_ERROR", "server_busy", 0, null);
        event(key, FROM.plusSeconds(4), null, null, "UPSTREAM_ERROR", "future_failure", 0, null);
        event(key, TO, "TOKEN", null, "OK", null, 900, null);
        JsonNode body = read(observer(false), "&orgId=" + first.getPublicId());
        assertThat(body.get("from").asText()).isEqualTo(FROM.toString());
        assertThat(body.get("to").asText()).isEqualTo(TO.toString());
        assertThat(body.get("generatedAt").asText()).isNotBlank();
        assertThat(body.get("summary").get("requests").asLong()).isEqualTo(5);
        assertThat(body.get("summary").get("unknownAxisRequests").asLong()).isEqualTo(3);
        assertThat(categories(body)).contains("LARGE_REQUEST", "ENDPOINT_POLICY", "GLOBAL_ADMISSION", "PROVIDER", "UNCLASSIFIED");
        long seriesRequests = 0;
        for (JsonNode point : body.get("series")) { seriesRequests += point.get("counts").get("requests").asLong(); }
        assertThat(seriesRequests).isEqualTo(5);
        assertThat(body.get("telemetryVisible").asBoolean()).isFalse();
        assertThat(body.get("telemetry").get("status").asText()).isEqualTo("NOT_PROVIDED");
    }

    @Test
    void scopeAndMoneyMaskPrecedeEveryAggregatePageAndSelector() throws Exception {
        UUID a = key(first, one, "visible key");
        UUID b = key(first, two, "hidden workspace key");
        UUID c = key(second, one, "hidden institution key");
        event(a, FROM, "TOKEN", "capacity-external", "OK", null, 13, "0.5");
        event(b, FROM, "TOKEN", null, "OK", null, 900, null);
        event(c, FROM, "TOKEN", null, "OK", null, 800, null);
        User actor = observer(false);
        JsonNode body = read(actor, "&group=KEY&size=1");
        assertThat(body.get("summary").get("inputTokens").asLong()).isEqualTo(13);
        assertThat(body.get("summary").get("costUsd").isNull()).isTrue();
        assertThat(body.get("consumers").get("totalElements").asLong()).isEqualTo(1);
        assertThat(body.get("consumers").get("items").get(0).get("keyId").asText()).isEqualTo(a.toString());
        assertThat(body.toString()).doesNotContain("hidden workspace key", "hidden institution key", two.getPublicId().toString());
        JsonNode money = read(observer(true), "&group=KEY");
        assertThat(money.get("summary").get("costUsd").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(money.get("summary").get("creditAxisRequests").asLong()).isZero();
    }

    @Test
    void exactPaginationAndErrorFilterUseTheSameServerRelation() throws Exception {
        for (int i = 0; i < 3; i++) {
            UUID key = key(first, one, "page key " + i);
            event(key, FROM, "TOKEN", null, "RATE_LIMITED", "quota_exhausted", i + 1, null);
            event(key, FROM.plusSeconds(1), "TOKEN", null, "OK", null, 200, null);
        }
        JsonNode body = read(observer(false), "&group=KEY&size=1&page=1&errorType=quota_exhausted");
        assertThat(body.get("summary").get("requests").asLong()).isEqualTo(3);
        assertThat(body.get("consumers").get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("consumers").get("items").size()).isEqualTo(1);
        assertThat(body.get("consumers").get("page").asInt()).isEqualTo(1);
        assertThat(body.get("consumers").get("totalPages").asInt()).isEqualTo(3);
        assertThat(body.get("reasons").size()).isEqualTo(1);
    }

    @Test
    void invalidAndFutureWindowsAreRefusedInsteadOfSilentlyNormalized() throws Exception {
        User actor = observer(false);
        request(actor, TO, FROM, "").andExpect(status().isUnprocessableContent());
        request(actor, FROM, FROM.plusSeconds(91L * 86400), "").andExpect(status().isUnprocessableContent());
        request(actor, Instant.now(), Instant.now().plusSeconds(100), "").andExpect(status().isUnprocessableContent());
        request(actor, FROM, TO, "&size=101").andExpect(status().isUnprocessableContent());
    }

    @Test
    void missingStartIsAFieldValidationError() throws Exception {
        assertMissingWindowParameters("?to=" + TO, "from");
    }

    @Test
    void missingEndIsAFieldValidationError() throws Exception {
        assertMissingWindowParameters("?from=" + FROM, "to");
    }

    @Test
    void missingBothWindowBoundsReportsBothFields() throws Exception {
        assertMissingWindowParameters("", "from", "to");
    }

    @Test
    void sweptHistoryIsUnavailableRatherThanObservedZero() throws Exception {
        jdbc.update("insert into llm_usage_rollup_state(id,swept_before) values(true,?)", java.sql.Date.valueOf("2021-02-04"));
        JsonNode body = read(observer(false), "");
        assertThat(body.get("quality").get("rawHistoryState").asText()).isEqualTo("FULLY_SWEPT");
        assertThat(body.get("summary").get("requests").isNull()).isTrue();
        assertThat(body.get("series").get(0).get("counts").get("requests").isNull()).isTrue();
    }

    @Test
    void missingAndLiteralErrorFiltersRemainDistinctAndMutuallyExclusive() throws Exception {
        UUID key = key(first, one, "missing type key");
        event(key, FROM, null, null, "UPSTREAM_ERROR", null, 1, null);
        event(key, FROM, null, null, "UPSTREAM_ERROR", "UNCLASSIFIED", 2, null);
        event(key, FROM, "TOKEN", null, "OK", null, 900, null);
        JsonNode missing = read(observer(false), "&errorTypeMissing=true&group=KEY");
        assertThat(missing.get("summary").get("requests").asLong()).isEqualTo(1);
        assertThat(missing.get("reasons").get(0).get("errorType").isNull()).isTrue();
        JsonNode literal = read(observer(false), "&errorType=UNCLASSIFIED");
        assertThat(literal.get("summary").get("inputTokens").asLong()).isEqualTo(2);
        request(observer(false), FROM, TO, "&errorType=UNCLASSIFIED&errorTypeMissing=true").andExpect(status().isUnprocessableContent());
    }

    @Test
    void scopedFiltersAndCurrentOverridesDoNotGrantWorkspaceAdministration() throws Exception {
        UUID key = key(first, one, "current override key");
        event(key, FROM, "TOKEN", null, "RATE_LIMITED", "quota_exhausted", 0, null);
        User actor = observer(false);
        JsonNode body = read(actor, "&workspaceId=" + one.getPublicId() + "&group=KEY&errorType=quota_exhausted");
        JsonNode limits = body.get("consumers").get("items").get(0).get("currentLimits");
        assertThat(limits.get("dailyTokens").asLong()).isEqualTo(1000);
        assertThat(limits.get("serviceDefaultsReported").asBoolean()).isFalse();
        assertThat(body.get("selectors").get("workspaces").get("items").get(0).get("id").asText()).isEqualTo(one.getPublicId().toString());
        request(actor, FROM, TO, "&workspaceId=" + two.getPublicId()).andExpect(status().isNotFound());
        request(actor, FROM, TO, "&orgId=" + second.getPublicId()).andExpect(status().isNotFound());
        request(owner, FROM, TO, "").andExpect(status().isForbidden());
        String token = jwt.createAccessToken(actor);
        jdbc.update("delete from user_org_roles where user_id=?", actor.getId());
        mvc.perform(get("/api/v1/admin/llm/capacity?from=" + FROM + "&to=" + TO)
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void registeredUpstreamFilterCountsOnlyFinalRecordedConsumption() throws Exception {
        String ref = "capacity-" + UUID.randomUUID();
        UUID upstream = jdbc.queryForObject("insert into llm_upstreams(ref,kind,display_name,org_id) values(?,'ON_PREM','시험 공급원',?) returning public_id",
                UUID.class, ref, first.getId());
        UUID hidden = jdbc.queryForObject("insert into llm_upstreams(ref,kind,display_name,org_id,dedicated) values(?,'ON_PREM','다른 공급원',?,true) returning public_id",
                UUID.class, ref + "-hidden", second.getId());
        UUID key = key(first, one, "final route key");
        event(key, FROM, "TOKEN", ref, "OK", null, 7, "0.2");
        event(key, FROM, null, null, "RATE_LIMITED", "large_request_busy", 0, null);
        JsonNode body = read(observer(false), "&upstreamId=" + upstream + "&group=KEY");
        assertThat(body.get("summary").get("requests").asLong()).isEqualTo(1);
        assertThat(body.get("summary").get("unknownFinalUpstreamRequests").asLong()).isZero();
        assertThat(body.get("reasons").size()).isZero();
        request(observer(false), FROM, TO, "&upstreamId=" + hidden).andExpect(status().isNotFound());
        request(observer(false), FROM, TO, "&upstreamId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void systemOnlyUnknownKeyCoverageDoesNotInventAnOwnerOrPhysicalDevice() throws Exception {
        String model = "capacity-unknown-" + UUID.randomUUID();
        jdbc.update("insert into llm_usage_events(event_id,public_model_name,status,error_type,input_tokens,output_tokens,estimated,latency_ms,requested_at) values(?,?,'UPSTREAM_ERROR',null,0,0,false,1,?)",
                UUID.randomUUID().toString(), model, Timestamp.from(FROM));
        JsonNode body = read(user(UserRole.SYS_VIEWER), "&publicModelName=" + model + "&group=KEY");
        assertThat(body.get("summary").get("unknownKeyRequests").asLong()).isEqualTo(1);
        assertThat(body.get("summary").get("unknownAxisRequests").asLong()).isEqualTo(1);
        assertThat(body.get("consumers").get("items").get(0).get("keyId").isNull()).isTrue();
        assertThat(body.get("consumers").get("items").get(0).get("currentLimits").isNull()).isTrue();
        assertThat(body.get("telemetryVisible").asBoolean()).isTrue();
        assertThat(body.get("telemetry").get("status").asText()).isEqualTo("NOT_CONFIGURED");
        JsonNode org = read(observer(false), "&publicModelName=" + model);
        assertThat(org.get("summary").get("requests").asLong()).isZero();
    }

    @Test
    void unknownAttemptsNeverBecomeProvenLocalAdmission() throws Exception {
        UUID local = key(first, one, "known zero attempts");
        UUID unknown = key(first, one, "unknown attempts");
        UUID positive = key(first, one, "missing route after attempt");
        UUID provider = key(first, one, "known final route");
        event(local, FROM, null, null, "RATE_LIMITED", "server_busy", 0, null); attempts(local, 0);
        event(unknown, FROM, null, null, "RATE_LIMITED", "server_busy", 0, null);
        event(positive, FROM, null, null, "RATE_LIMITED", "server_busy", 0, null); attempts(positive, 1);
        event(provider, FROM, "TOKEN", "capacity-provider", "UPSTREAM_ERROR", "server_busy", 0, null); attempts(provider, 1);
        JsonNode body = read(observer(false), "&errorType=server_busy");
        Map<String, Long> counts = new java.util.HashMap<>();
        body.get("reasons").forEach(reason -> counts.put(reason.get("category").asText(), reason.get("requests").asLong()));
        assertThat(counts).containsEntry("GLOBAL_ADMISSION", 1L).containsEntry("PROVIDER", 1L).containsEntry("UNCLASSIFIED", 2L);
    }

    @Test
    void categoryAndRawTypeSelectTheExactReasonKeysAcrossEveryProjection() throws Exception {
        UUID local = key(first, one, "local reason key");
        UUID provider = key(first, one, "provider reason key");
        UUID canceled = key(first, one, "canceled missing reason key");
        UUID unknown = key(first, one, "unknown missing reason key");
        event(local, FROM, null, null, "RATE_LIMITED", "server_busy", 0, null); attempts(local, 0);
        event(provider, FROM, "TOKEN", "capacity-provider", "UPSTREAM_ERROR", "server_busy", 0, null); attempts(provider, 1);
        event(canceled, FROM, null, null, "CANCELED", null, 0, null);
        event(unknown, FROM, null, null, "UPSTREAM_ERROR", null, 0, null);
        User actor = observer(false);
        assertExactReasonKey(read(actor, "&group=KEY&errorType=server_busy&reasonCategory=GLOBAL_ADMISSION"), local);
        assertExactReasonKey(read(actor, "&group=KEY&errorType=server_busy&reasonCategory=PROVIDER"), provider);
        assertExactReasonKey(read(actor, "&group=KEY&errorTypeMissing=true&reasonCategory=CANCELED"), canceled);
        assertExactReasonKey(read(actor, "&group=KEY&errorTypeMissing=true&reasonCategory=UNCLASSIFIED"), unknown);
        JsonNode empty = read(actor, "&group=KEY&errorType=server_busy&reasonCategory=ENDPOINT_POLICY");
        assertThat(empty.get("summary").get("requests").asLong()).isZero();
        assertThat(empty.get("consumers").get("totalElements").asLong()).isZero();
        assertThat(empty.get("reasons").size()).isZero();
    }

    @Test
    void mixedObservedSourcesNeverPublishPartialMoneyAsTheWholeAmount() throws Exception {
        UUID a = key(first, one, "observed without cost");
        UUID b = key(second, one, "observed with cost");
        event(a, FROM, "TOKEN", "capacity-provider", "OK", null, 13, "0.5");
        event(b, FROM, "TOKEN", "capacity-provider", "OK", null, 17, "0.7");
        User actor = observer(false);
        grant(actor, second, Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ));
        JsonNode all = read(actor, "");
        assertThat(all.get("summary").get("inputTokens").asLong()).isEqualTo(30);
        assertThat(all.get("summary").get("costUsd").isNull()).isTrue();
        assertThat(all.get("consumers").get("items").get(0).get("counts").get("pricedRequests").isNull()).isTrue();
        JsonNode scoped = read(actor, "&orgId=" + second.getPublicId());
        assertThat(scoped.get("summary").get("costUsd").decimalValue()).isEqualByComparingTo("0.7");
    }

    @Test
    void partialSweepPreservesRetainedCountsAndKeepsOldBucketsUnavailable() throws Exception {
        Instant from = Instant.parse("2021-02-03T14:30:00Z");
        Instant to = from.plusSeconds(3600);
        UUID key = key(first, one, "sweep boundary key");
        event(key, from.plusSeconds(300), "TOKEN", null, "OK", null, 100, null);
        event(key, from.plusSeconds(2100), "TOKEN", null, "OK", null, 7, null);
        jdbc.update("insert into llm_usage_rollup_state(id,swept_before) values(true,?)", java.sql.Date.valueOf("2021-02-04"));
        JsonNode body = json.readTree(request(observer(false), from, to, "&bucketSeconds=1800").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(body.get("quality").get("rawHistoryState").asText()).isEqualTo("PARTIALLY_SWEPT");
        assertThat(body.get("summary").get("inputTokens").asLong()).isEqualTo(7);
        assertThat(body.get("series").get(0).get("counts").get("requests").isNull()).isTrue();
        assertThat(body.get("series").get(1).get("counts").get("inputTokens").asLong()).isEqualTo(7);
    }

    private void assertExactReasonKey(JsonNode body, UUID key) {
        assertThat(body.get("summary").get("requests").asLong()).isEqualTo(1);
        assertThat(body.get("consumers").get("totalElements").asLong()).isEqualTo(1);
        assertThat(body.get("consumers").get("items").get(0).get("keyId").asText()).isEqualTo(key.toString());
        assertThat(body.get("reasons").size()).isEqualTo(1);
        long requests = 0;
        for (JsonNode point : body.get("series")) { requests += point.get("counts").get("requests").asLong(); }
        assertThat(requests).isEqualTo(1);
    }

    private void attempts(UUID key, int value) {
        jdbc.update("update llm_usage_events set attempts=? where key_id=(select id from llm_api_keys where public_id=?)", value, key);
    }

    private java.util.List<String> categories(JsonNode body) {
        java.util.List<String> result = new java.util.ArrayList<>();
        body.get("reasons").forEach(reason -> result.add(reason.get("category").asText()));
        return result;
    }

    private User user(UserRole role) {
        User value = new User("capacity-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "수요 시험");
        value.setRole(role); value.setStatus(UserStatus.ACTIVE); value.setEmailVerifiedAt(Instant.now());
        return users.save(value);
    }

    private User observer(boolean money) {
        User actor = user(UserRole.ORG_VIEWER);
        Set<InstitutionWork> work = money ? Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ) : Set.of(InstitutionWork.LLM_OBSERVE);
        grant(actor, first, work);
        return actor;
    }

    private void grant(User actor, Org org, Set<InstitutionWork> work) {
        var assignment = InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(), work,
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(one.getPublicId()))), 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))",
                actor.getId(), org.getId(), assignment.displayRole().name(), encoded.mode(), encoded.payload());
    }

    private UUID key(Org org, Workspace workspace, String name) {
        long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('LLM_API_KEY',?,?,?,'수요 시험',?) returning id", Long.class,
                workspace.getId(), org.getId(), owner.getId(), name);
        jdbc.update("insert into llm_key_request_details(request_id,req_passthrough_endpoints) values(?,'[\"chat\"]')", request);
        return jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,status,daily_tokens,credit_limit,created_by) values(?,?,?,?,'수요 시험','ACTIVE',1000,0,?) returning public_id",
                UUID.class, workspace.getId(), org.getId(), request, name, owner.getId());
    }

    private void event(UUID key, Instant at, String axis, String upstream, String state, String error, int tokens, String cost) {
        jdbc.update("insert into llm_usage_events(event_id,key_id,public_model_name,budget_axis,upstream_ref,status,error_type,input_tokens,output_tokens,estimated,latency_ms,requested_at,cost_usd) select ?,id,'capacity-model',?,?,?,?,?,0,false,1,?,? from llm_api_keys where public_id=?",
                UUID.randomUUID().toString(), axis, upstream, state, error, tokens, Timestamp.from(at), cost == null ? null : new java.math.BigDecimal(cost), key);
    }

    private org.springframework.test.web.servlet.ResultActions request(User actor, Instant from, Instant to, String query) throws Exception {
        return mvc.perform(get("/api/v1/admin/llm/capacity?from=" + from + "&to=" + to + query)
                .header("Authorization", "Bearer " + jwt.createAccessToken(actor)));
    }

    private void assertMissingWindowParameters(String query, String... fields) throws Exception {
        User actor = observer(false);
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/admin/llm/capacity" + query)
                .header("Authorization", "Bearer " + jwt.createAccessToken(actor)))
                .andExpect(status().isUnprocessableContent()).andReturn().getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> reported = new java.util.ArrayList<>();
        body.get("errors").forEach(error -> {
            reported.add(error.get("field").asText());
            assertThat(error.get("message").asText()).isNotBlank();
        });
        assertThat(reported).containsExactly(fields);
    }

    private JsonNode read(User actor, String query) throws Exception {
        return json.readTree(request(actor, FROM, TO, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
