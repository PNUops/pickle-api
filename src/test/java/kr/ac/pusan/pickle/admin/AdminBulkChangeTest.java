package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.LlmSyncService;
import kr.ac.pusan.pickle.llm.dto.LlmSyncRequest;
import kr.ac.pusan.pickle.llm.dto.LlmSyncResponse;
import kr.ac.pusan.pickle.llm.openrouter.LlmOpenRouterProvisioner;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Administrator bulk changes: one change, many targets, judged per target.
 *
 * <p>Every fixture row is inserted straight through JDBC, so nothing here
 * passed approval; what each test then exercises is the bulk surface's own
 * judgment, not the fixtures' realism. The JobRunr server is off in the test
 * profile, so after-commit enqueues leave rows rather than running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminBulkChangeTest {

    private static final String PREVIEW = "/api/v1/admin/bulk-changes/preview";
    private static final String APPLY = "/api/v1/admin/bulk-changes";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    private LlmSyncService llmSyncService;
    @Autowired
    private LlmOpenRouterProvisioner provisioner;

    private Org orgA;
    private Org orgB;
    private long workspaceA;
    private long workspaceB;
    private User requester;
    private User outsider;
    private String userToken;
    private String orgViewerToken;
    private String orgManagerToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String sysManagerToken;
    private String sysAdminToken;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ClockConfig.KST);
        orgA = org("일괄 변경 테스트 기관 A");
        orgB = org("일괄 변경 테스트 기관 B");
        workspaceA = workspace("일괄 변경 A");
        workspaceB = workspace("일괄 변경 B");
        requester = user("adminbulk.requester@pusan.ac.kr", "일괄 신청자", UserRole.USER, null);
        outsider = user("adminbulk.outsider@pusan.ac.kr", "워크스페이스 밖", UserRole.USER, null);
        userToken = token(requester);
        for (long workspaceId : new long[] {workspaceA, workspaceB}) {
            jdbcTemplate.update("insert into workspace_members (workspace_id, user_id, role) "
                            + "values (?, ?, 'OWNER'::workspace_member_role) on conflict do nothing",
                    workspaceId, requester.getId());
        }
        orgViewerToken = token(user("adminbulk.orgviewer@pusan.ac.kr", "기관 열람자",
                UserRole.ORG_VIEWER, orgA.getId()));
        orgManagerToken = token(user("adminbulk.orgmanager@pusan.ac.kr", "기관 운영자",
                UserRole.ORG_MANAGER, orgA.getId()));
        orgAdminToken = token(user("adminbulk.orgadmin@pusan.ac.kr", "기관 관리자",
                UserRole.ORG_ADMIN, orgA.getId()));
        otherOrgAdminToken = token(user("adminbulk.otherorgadmin@pusan.ac.kr", "타 기관 관리자",
                UserRole.ORG_ADMIN, orgB.getId()));
        sysManagerToken = token(user("adminbulk.sysmanager@pusan.ac.kr", "시스템 운영자",
                UserRole.SYS_MANAGER, null));
        sysAdminToken = token(user("adminbulk.sysadmin@pusan.ac.kr", "시스템 관리자",
                UserRole.SYS_ADMIN, null));
    }

    // ── LLM key limits ─────────────────────────────────────────────────────

    /**
     * The whole point of the merge: a change that names one field leaves the
     * other eight as each key has them, the three lists included, which is
     * what the single replacement cannot promise.
     */
    @Test
    void absentLimitFieldsStayAsTheyAre() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "한 필드만 바꾸는 키", "ACTIVE", null);
        setLists(key, "[\"openai/*\"]", "[]", "[\"images\"]");

        JsonNode preview = previewJson(sysAdminToken,
                request("LLM_KEY", List.of(key.publicId()), limits(Map.of("rpm", 90))));
        JsonNode previewed = item(preview, key.publicId());
        assertThat(previewed.get("applicable").asBoolean()).isTrue();
        assertThat(previewed.get("name").asString()).isEqualTo("한 필드만 바꾸는 키");
        assertThat(previewed.get("fields")).hasSize(1);
        assertThat(previewed.get("fields").get(0).get("field").asString()).isEqualTo("rpm");
        assertThat(previewed.get("fields").get(0).get("oldValue").asInt()).isEqualTo(60);
        assertThat(previewed.get("fields").get(0).get("newValue").asInt()).isEqualTo(90);

        JsonNode apply = applyJson(sysAdminToken,
                request("LLM_KEY", List.of(key.publicId()), limits(Map.of("rpm", 90))));
        assertThat(item(apply, key.publicId()).get("result").asString()).isEqualTo("APPLIED");

        Map<String, Object> row = keyRow(key);
        assertThat(row.get("rpm")).isEqualTo(90);
        assertThat(row.get("tpm")).isEqualTo(1000);
        assertThat(row.get("concurrency")).isEqualTo(4);
        assertThat(models(key, "credit_allowed_models")).containsExactly("openai/*");
        assertThat(models(key, "passthrough_endpoints")).containsExactly("images");

        Map<String, Object> detail = auditDetail(key.publicId(), "llm_key.limits_update");
        assertThat(((Map<?, ?>) detail.get("old")).get("rpm")).isEqualTo(60);
        assertThat(((Map<?, ?>) detail.get("new")).get("rpm")).isEqualTo(90);
        assertThat(((Map<?, ?>) detail.get("new")).get("passthroughEndpoints"))
                .isEqualTo(List.of("images"));
        assertThat(detail.get("batchId")).isEqualTo(apply.get("batchId").asString());
    }

    @Test
    void listOperationsMergeOntoEachKeysOwnList() throws Exception {
        Key first = key(orgA.getId(), workspaceA, "목록 병합 키 1", "ACTIVE", null);
        Key second = key(orgA.getId(), workspaceA, "목록 병합 키 2", "ACTIVE", null);
        Key third = key(orgA.getId(), workspaceA, "목록 병합 키 3", "ACTIVE", null);
        setLists(first, "[\"openai/*\"]", "[]", "[]");
        setLists(second, "[\"anthropic/*\"]", "[]", "[]");
        setLists(third, "[]", "[]", "[\"images\", \"embeddings\"]");

        JsonNode added = applyJson(sysAdminToken, request("LLM_KEY",
                List.of(first.publicId(), second.publicId()),
                limits(Map.of("creditAllowedModels", listOp("ADD", List.of("anthropic/*"))))));
        assertThat(item(added, first.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(added, second.publicId()).get("result").asString()).isEqualTo("UNCHANGED");
        assertThat(models(first, "credit_allowed_models")).containsExactly("openai/*", "anthropic/*");
        assertThat(models(second, "credit_allowed_models")).containsExactly("anthropic/*");

        JsonNode removed = applyJson(sysAdminToken, request("LLM_KEY", List.of(first.publicId()),
                limits(Map.of("creditAllowedModels", listOp("REMOVE", List.of("openai/*"))))));
        assertThat(item(removed, first.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(models(first, "credit_allowed_models")).containsExactly("anthropic/*");

        JsonNode cleared = applyJson(sysAdminToken, request("LLM_KEY", List.of(third.publicId()),
                limits(Map.of("passthroughEndpoints", listOp("REPLACE", List.of())))));
        assertThat(item(cleared, third.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(models(third, "passthrough_endpoints")).isEmpty();
        // The lists untouched by these three changes are still what they were.
        assertThat(models(third, "credit_allowed_models")).isEmpty();
        assertThat(models(first, "passthrough_endpoints")).isEmpty();
    }

    /**
     * The money gate is judged per key against what the merge actually moves.
     * The same request is a rate-limit edit on a key already at that amount
     * and a money change on one that is not, and only the second is refused.
     */
    @Test
    void sysManagerMayMoveOnlyTheNonMoneyFields() throws Exception {
        Key already = key(orgA.getId(), workspaceA, "이미 5달러인 키", "ACTIVE", null);
        Key funded = key(orgA.getId(), workspaceA, "1달러인 키", "ACTIVE", null);
        jdbcTemplate.update("update llm_api_keys set credit_limit = 5.00 where public_id = ?",
                already.publicId());
        Map<String, Object> body = request("LLM_KEY", List.of(already.publicId(), funded.publicId()),
                limits(Map.of("creditLimit", "5.00", "rpm", 77)));

        JsonNode preview = previewJson(sysManagerToken, body);
        assertThat(item(preview, already.publicId()).get("applicable").asBoolean()).isTrue();
        assertThat(item(preview, funded.publicId()).get("applicable").asBoolean()).isFalse();
        assertThat(item(preview, funded.publicId()).get("reason").asString()).isEqualTo("FORBIDDEN");

        JsonNode apply = applyJson(sysManagerToken, body);
        assertThat(item(apply, already.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(apply, funded.publicId()).get("result").asString()).isEqualTo("SKIPPED");
        assertThat(item(apply, funded.publicId()).get("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(keyRow(already).get("rpm")).isEqualTo(77);
        assertThat(keyRow(funded).get("rpm")).isEqualTo(60);
    }

    @Test
    void aTargetOutsideTheOperatedInstitutionIsReportedAsMissing() throws Exception {
        Key own = key(orgA.getId(), workspaceA, "자기 기관 키", "ACTIVE", null);
        Key foreign = key(orgB.getId(), workspaceB, "타 기관 키", "ACTIVE", null);
        Map<String, Object> body = request("LLM_KEY", List.of(own.publicId(), foreign.publicId()),
                limits(Map.of("concurrency", 8)));

        JsonNode preview = previewJson(orgManagerToken, body);
        JsonNode masked = item(preview, foreign.publicId());
        assertThat(masked.get("applicable").asBoolean()).isFalse();
        assertThat(masked.get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(masked.get("name").isNull()).isTrue();

        JsonNode apply = applyJson(orgManagerToken, body);
        assertThat(item(apply, own.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(apply, foreign.publicId()).get("result").asString()).isEqualTo("SKIPPED");
        assertThat(item(apply, foreign.publicId()).get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(keyRow(own).get("concurrency")).isEqualTo(8);
        assertThat(keyRow(foreign).get("concurrency")).isEqualTo(4);

        // An id nothing answers to reads exactly like the foreign key.
        JsonNode unknown = previewJson(orgManagerToken, request("LLM_KEY",
                List.of(SeedFixtures.UNKNOWN_ID), limits(Map.of("concurrency", 8))));
        assertThat(item(unknown, SeedFixtures.UNKNOWN_ID).get("reason").asString())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void aTargetChangedSinceThePreviewIsStaleAndLeftAlone() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "미리보기 뒤 바뀐 키", "ACTIVE", null);
        Map<String, Object> body = request("LLM_KEY", List.of(key.publicId()),
                limits(Map.of("rpm", 90)));
        Map<String, String> fingerprints = fingerprintsOf(previewJson(sysAdminToken, body));
        jdbcTemplate.update("update llm_api_keys set rpm = 61 where public_id = ?", key.publicId());
        long generation = generation();

        JsonNode apply = apply(sysAdminToken, body, fingerprints);
        assertThat(item(apply, key.publicId()).get("result").asString()).isEqualTo("STALE");
        assertThat(keyRow(key).get("rpm")).isEqualTo(61);
        assertThat(generation()).isEqualTo(generation);
        assertThat(auditCount(key.publicId(), "llm_key.limits_update")).isZero();
    }

    @Test
    void aMergedResultBreakingTheRulesIsSkipped() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "규칙에 걸리는 키", "ACTIVE", null);
        Map<String, Object> body = request("LLM_KEY", List.of(key.publicId()),
                limits(Map.of("tpm", 30)));

        JsonNode preview = previewJson(sysAdminToken, body);
        assertThat(item(preview, key.publicId()).get("reason").asString()).isEqualTo("VALIDATION");
        JsonNode apply = applyJson(sysAdminToken, body);
        assertThat(item(apply, key.publicId()).get("result").asString()).isEqualTo("SKIPPED");
        assertThat(item(apply, key.publicId()).get("reason").asString()).isEqualTo("VALIDATION");
        assertThat(keyRow(key).get("tpm")).isEqualTo(1000);
    }

    @Test
    void theGenerationMovesOncePerApplyThatWritesAndNeverOnPreview() throws Exception {
        Key first = key(orgA.getId(), workspaceA, "세대 키 1", "ACTIVE", null);
        Key second = key(orgA.getId(), workspaceA, "세대 키 2", "ACTIVE", null);
        Map<String, Object> body = request("LLM_KEY", List.of(first.publicId(), second.publicId()),
                limits(Map.of("concurrency", 7)));
        long before = generation();

        previewJson(sysAdminToken, body);
        assertThat(generation()).isEqualTo(before);

        JsonNode apply = applyJson(sysAdminToken, body);
        assertThat(item(apply, first.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(apply, second.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(generation()).isEqualTo(before + 1);

        JsonNode again = applyJson(sysAdminToken, body);
        assertThat(item(again, first.publicId()).get("result").asString()).isEqualTo("UNCHANGED");
        assertThat(item(again, second.publicId()).get("result").asString()).isEqualTo("UNCHANGED");
        assertThat(generation()).isEqualTo(before + 1);
    }

    // ── LLM key status ─────────────────────────────────────────────────────

    @Test
    void statusChangesFollowTheSinglePathAndRevokeKeepsItsNarrowerGate() throws Exception {
        Key active = key(orgA.getId(), workspaceA, "상태 키 활성", "ACTIVE", null);
        Key suspended = key(orgA.getId(), workspaceA, "상태 키 정지", "SUSPENDED", null);
        List<UUID> both = List.of(active.publicId(), suspended.publicId());

        JsonNode suspend = applyJson(orgManagerToken, request("LLM_KEY", both,
                Map.of("kind", "LLM_KEY_STATUS", "llmKeyStatus",
                        Map.of("action", "SUSPEND", "reason", "일괄 점검"))));
        assertThat(item(suspend, active.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(suspend, suspended.publicId()).get("result").asString())
                .isEqualTo("UNCHANGED");
        assertThat(keyRow(active).get("status")).isEqualTo("SUSPENDED");
        Map<String, Object> detail = auditDetail(active.publicId(), "llm_key.suspend");
        assertThat(((Map<?, ?>) detail.get("old")).get("status")).isEqualTo("ACTIVE");
        assertThat(((Map<?, ?>) detail.get("new")).get("status")).isEqualTo("SUSPENDED");
        assertThat(detail.get("reason")).isEqualTo("일괄 점검");
        assertThat(detail.get("batchId")).isEqualTo(suspend.get("batchId").asString());

        JsonNode resume = applyJson(orgManagerToken, request("LLM_KEY", both,
                Map.of("kind", "LLM_KEY_STATUS", "llmKeyStatus", Map.of("action", "RESUME"))));
        assertThat(item(resume, active.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(resume, suspended.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(((Map<?, ?>) auditDetail(suspended.publicId(), "llm_key.resume").get("old"))
                .get("status")).isEqualTo("SUSPENDED");

        // Revoking is the holder's standing right and the administrators' of the
        // tier above; an operator who may suspend may not revoke.
        Map<String, Object> revoke = request("LLM_KEY", both,
                Map.of("kind", "LLM_KEY_STATUS", "llmKeyStatus", Map.of("action", "REVOKE")));
        JsonNode refused = previewJson(orgManagerToken, revoke);
        assertThat(item(refused, active.publicId()).get("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(item(previewJson(sysManagerToken, revoke), active.publicId())
                .get("reason").asString()).isEqualTo("FORBIDDEN");

        JsonNode revoked = applyJson(orgAdminToken, revoke);
        assertThat(item(revoked, active.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(keyRow(active).get("status")).isEqualTo("REVOKED");
        assertThat(auditCount(active.publicId(), "llm_key.revoke")).isEqualTo(1);
        JsonNode again = applyJson(orgAdminToken, revoke);
        assertThat(item(again, active.publicId()).get("result").asString()).isEqualTo("UNCHANGED");

        // A revoked key's limits are no longer anybody's to edit.
        JsonNode limits = previewJson(sysAdminToken, request("LLM_KEY", List.of(active.publicId()),
                limits(Map.of("rpm", 90))));
        assertThat(item(limits, active.publicId()).get("reason").asString())
                .isEqualTo("INVALID_STATE");
    }

    // ── LLM key expiry ─────────────────────────────────────────────────────

    @Test
    void expiryMovesOnTheSinglePathAndInBulkAndRevivesALapsedKey() throws Exception {
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        Key single = key(orgA.getId(), workspaceA, "만료일 단일 키", "ACTIVE", yesterday);
        Key bulk = key(orgA.getId(), workspaceA, "만료일 일괄 키", "ACTIVE", yesterday);
        Key revoked = key(orgA.getId(), workspaceA, "만료일 폐기 키", "REVOKED", null);
        Key provisioned = key(orgA.getId(), workspaceA, "만료일 발급 키", "ACTIVE",
                today.plusDays(61).atStartOfDay(ClockConfig.KST).toInstant());
        // The hash and the ciphertext are a pair the schema checks together.
        jdbcTemplate.update("update llm_api_keys set openrouter_key_hash = 'or-hash-bulk', "
                + "openrouter_key_enc = 'enc-bulk' where public_id = ?", provisioned.publicId());
        LocalDate endDate = today.plusDays(30);
        Instant expected = endDate.plusDays(1).atStartOfDay(ClockConfig.KST).toInstant();

        mockMvc.perform(patch("/api/v1/admin/llm/keys/" + single.publicId() + "/expiry")
                        .header("Authorization", "Bearer " + orgManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + today.minusDays(1) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
        mockMvc.perform(patch("/api/v1/admin/llm/keys/" + single.publicId() + "/expiry")
                        .header("Authorization", "Bearer " + orgManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + endDate + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(expected.toString()));
        Map<String, Object> detail = auditDetail(single.publicId(), "llm_key.expiry_update");
        assertThat(((Map<?, ?>) detail.get("old")).get("expiresAt").toString())
                .startsWith(yesterday.toString().substring(0, 19));
        assertThat(((Map<?, ?>) detail.get("new")).get("expiresAt")).isEqualTo(expected.toString());
        // A key with a vendor half may be shortened but not extended: the
        // vendor fixes its expiry at creation, and the gateway enforces ours.
        mockMvc.perform(patch("/api/v1/admin/llm/keys/" + provisioned.publicId() + "/expiry")
                        .header("Authorization", "Bearer " + orgManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + today.plusDays(90) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LLM_KEY_INVALID_STATE"));

        List<UUID> targets = List.of(bulk.publicId(), revoked.publicId(), provisioned.publicId());
        Map<String, Object> extend = Map.of("kind", "LLM_KEY_EXPIRY",
                "llmKeyExpiry", Map.of("endDate", today.plusDays(90).toString()));
        assertThat(item(previewJson(orgManagerToken, request("LLM_KEY", targets, extend)),
                provisioned.publicId()).get("reason").asString()).isEqualTo("INELIGIBLE");
        Map<String, Object> change = Map.of("kind", "LLM_KEY_EXPIRY",
                "llmKeyExpiry", Map.of("endDate", endDate.toString()));
        JsonNode preview = previewJson(orgManagerToken, request("LLM_KEY", targets, change));
        JsonNode revived = item(preview, bulk.publicId());
        assertThat(revived.get("applicable").asBoolean()).isTrue();
        assertThat(revived.get("fields")).hasSize(2);
        assertThat(revived.get("fields").get(0).get("field").asString()).isEqualTo("expiresAt");
        assertThat(revived.get("fields").get(1).get("oldValue").asString()).isEqualTo("EXPIRED");
        assertThat(revived.get("fields").get(1).get("newValue").asString()).isEqualTo("ACTIVE");
        assertThat(item(preview, revoked.publicId()).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(item(preview, provisioned.publicId()).get("applicable").asBoolean()).isTrue();

        long generation = generation();
        JsonNode apply = applyJson(orgManagerToken, request("LLM_KEY", targets, change));
        assertThat(item(apply, bulk.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(apply, revoked.publicId()).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(item(apply, provisioned.publicId()).get("result").asString())
                .isEqualTo("APPLIED");
        assertThat(generation()).isEqualTo(generation + 1);
        // The shortened key keeps its vendor half: the provisioning path only
        // serves keys that have none, so it leaves this one alone.
        provisioner.provision(provisioned.id());
        Map<String, Object> vendorHalf = jdbcTemplate.queryForMap("select openrouter_key_hash, "
                + "openrouter_last_error, openrouter_attempt_count from llm_api_keys where id = ?",
                provisioned.id());
        assertThat(vendorHalf.get("openrouter_key_hash")).isEqualTo("or-hash-bulk");
        assertThat(vendorHalf.get("openrouter_last_error")).isNull();
        assertThat(vendorHalf.get("openrouter_attempt_count")).isEqualTo(0);
        assertThat(auditDetail(bulk.publicId(), "llm_key.expiry_update").get("batchId"))
                .isEqualTo(apply.get("batchId").asString());

        // The gateway reads the expiry off the document, so the revived key is
        // served ACTIVE with its new date.
        LlmSyncResponse document = llmSyncService.sync(objectMapper.convertValue(
                Map.of("appliedGeneration", 0, "supportedFormat", 1, "inFlight", 0),
                LlmSyncRequest.class));
        assertThat(document).isInstanceOf(LlmSyncResponse.Document.class);
        LlmSyncResponse.KeyEntry served = ((LlmSyncResponse.Document) document).keys().stream()
                .filter(entry -> entry.keyId().equals(bulk.publicId().toString()))
                .findFirst().orElseThrow();
        assertThat(served.expiresAt()).isEqualTo(expected);
        assertThat(served.status()).isEqualTo("ACTIVE");

        mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + orgManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("LLM_KEY", targets,
                                Map.of("kind", "LLM_KEY_EXPIRY", "llmKeyExpiry",
                                        Map.of("endDate", today.minusDays(1).toString()))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("change.llmKeyExpiry.endDate"));
    }

    // ── VM ─────────────────────────────────────────────────────────────────

    @Test
    void vmPeriodBulkAppliesAndSkipsPerVm() throws Exception {
        long moving = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(10));
        long bound = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(10));
        long already = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(30));
        jdbcTemplate.update("update vms set delete_scheduled_for = now() where id = ?", bound);
        List<UUID> targets = List.of(pub("vms", moving), pub("vms", bound), pub("vms", already));
        Map<String, Object> change = Map.of("kind", "VM_PERIOD",
                "vmPeriod", Map.of("endDate", today.plusDays(30).toString()));

        JsonNode apply = applyJson(orgManagerToken, request("VM", targets, change));
        assertThat(item(apply, pub("vms", moving)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(apply, pub("vms", bound)).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(item(apply, pub("vms", already)).get("result").asString())
                .isEqualTo("UNCHANGED");
        assertThat(jdbcTemplate.queryForObject("select end_date from vms where id = ?",
                LocalDate.class, moving)).isEqualTo(today.plusDays(30));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = 'PERIOD_UPDATE'",
                Long.class, moving)).isEqualTo(1);
        assertThat(auditDetail(pub("vms", moving), "vm.period_update").get("batchId"))
                .isEqualTo(apply.get("batchId").asString());

        JsonNode cleared = applyJson(orgManagerToken, request("VM", List.of(pub("vms", moving)),
                Map.of("kind", "VM_PERIOD", "vmPeriod", Map.of("clearEndDate", true))));
        assertThat(item(cleared, pub("vms", moving)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject("select end_date from vms where id = ?",
                LocalDate.class, moving)).isNull();

        mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + orgManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("VM", targets,
                                Map.of("kind", "VM_PERIOD", "vmPeriod", Map.of(
                                        "endDate", today.plusDays(30).toString(),
                                        "clearEndDate", true))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("change.vmPeriod.endDate"));
    }

    @Test
    void vmPowerBulkClaimsIntentsAndReportsWhyNot() throws Exception {
        long stopped = createVm(orgA.getId(), workspaceA, "STOPPED", today.plusDays(10));
        long running = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(10));
        long expired = createVm(orgA.getId(), workspaceA, "STOPPED", today.minusDays(2));
        List<UUID> targets = List.of(pub("vms", stopped), pub("vms", running), pub("vms", expired));

        JsonNode start = applyJson(orgManagerToken, request("VM", targets,
                Map.of("kind", "VM_POWER", "vmPower", Map.of("action", "START"))));
        assertThat(item(start, pub("vms", stopped)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(start, pub("vms", running)).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(item(start, pub("vms", expired)).get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select pending_power_action from vms where id = ?", String.class, stopped))
                .isEqualTo("START");
        assertThat(auditDetail(pub("vms", stopped), "vm.admin_start").get("batchId"))
                .isEqualTo(start.get("batchId").asString());

        JsonNode forceStop = applyJson(sysManagerToken, request("VM", List.of(pub("vms", running)),
                Map.of("kind", "VM_POWER", "vmPower", Map.of("action", "FORCE_STOP"))));
        assertThat(item(forceStop, pub("vms", running)).get("result").asString())
                .isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject(
                "select pending_power_action from vms where id = ?", String.class, running))
                .isEqualTo("FORCE_STOP");
    }

    @Test
    void vmDeletionBulkSchedulesAndCancelsWithinTheAdministratorTier() throws Exception {
        long plain = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(10));
        long shielded = createVm(orgA.getId(), workspaceA, "RUNNING", today.plusDays(10));
        long broken = createVm(orgA.getId(), workspaceA, "ERROR", today.plusDays(10));
        jdbcTemplate.update("insert into vm_settings (vm_id, key, value) values (?, ?, ?::jsonb)",
                shielded, "deletion_protection", "true");
        List<UUID> targets = List.of(pub("vms", plain), pub("vms", shielded), pub("vms", broken));
        Map<String, Object> schedule = request("VM", targets, Map.of("kind", "VM_DELETION",
                "vmDeletion", Map.of("action", "SCHEDULE",
                        "scheduledFor", Instant.now().plus(3, ChronoUnit.DAYS).toString(),
                        "reason", "일괄 삭제 시험")));

        for (String denied : new String[] {orgManagerToken, sysManagerToken}) {
            assertThat(item(previewJson(denied, schedule), pub("vms", plain))
                    .get("reason").asString()).isEqualTo("FORBIDDEN");
        }
        assertThat(item(previewJson(otherOrgAdminToken, schedule), pub("vms", plain))
                .get("reason").asString()).isEqualTo("NOT_FOUND");

        JsonNode scheduled = applyJson(orgAdminToken, schedule);
        assertThat(item(scheduled, pub("vms", plain)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(scheduled, pub("vms", shielded)).get("reason").asString())
                .isEqualTo("PROTECTED");
        assertThat(item(scheduled, pub("vms", broken)).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(jdbcTemplate.queryForObject("select delete_kind from vms where id = ?",
                String.class, plain)).isEqualTo("ADMIN");
        assertThat(auditDetail(pub("vms", plain), "vm.schedule_delete").get("batchId"))
                .isEqualTo(scheduled.get("batchId").asString());

        JsonNode canceled = applyJson(orgAdminToken, request("VM",
                List.of(pub("vms", plain), pub("vms", shielded)),
                Map.of("kind", "VM_DELETION", "vmDeletion", Map.of("action", "CANCEL"))));
        assertThat(item(canceled, pub("vms", plain)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(canceled, pub("vms", shielded)).get("reason").asString())
                .isEqualTo("INVALID_STATE");
        assertThat(jdbcTemplate.queryForObject("select delete_kind from vms where id = ?",
                String.class, plain)).isNull();

        mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("VM", targets,
                                Map.of("kind", "VM_DELETION", "vmDeletion", Map.of(
                                        "action", "SCHEDULE",
                                        "scheduledFor", Instant.now().plus(3, ChronoUnit.DAYS)
                                                .toString()))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("change.vmDeletion.reason"));
    }

    // ── access ─────────────────────────────────────────────────────────────

    @Test
    void accessBulkIsAnAdministratorsInterventionOnTheOwnersList() throws Exception {
        long first = createVm(orgA.getId(), workspaceA, "STOPPED", today.plusDays(10));
        long second = createVm(orgA.getId(), workspaceA, "STOPPED", today.plusDays(10));
        List<UUID> targets = List.of(pub("vms", first), pub("vms", second));
        Map<String, Object> grant = request("VM", targets, access("GRANT", requester, "MEMBER"));

        assertThat(item(previewJson(orgManagerToken, grant), pub("vms", first))
                .get("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(item(previewJson(sysManagerToken, grant), pub("vms", first))
                .get("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(item(previewJson(otherOrgAdminToken, grant), pub("vms", first))
                .get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(item(previewJson(orgAdminToken, request("VM", targets,
                access("GRANT", outsider, "MEMBER"))), pub("vms", first))
                .get("reason").asString()).isEqualTo("NOT_MEMBER");
        Map<String, Object> nobody = new LinkedHashMap<>(access("GRANT", requester, "MEMBER"));
        nobody.put("access", Map.of("userId", SeedFixtures.UNKNOWN_ID.toString(),
                "action", "GRANT", "role", "MEMBER"));
        assertThat(item(previewJson(orgAdminToken, request("VM", targets, nobody)),
                pub("vms", first)).get("reason").asString()).isEqualTo("INELIGIBLE");

        JsonNode granted = applyJson(orgAdminToken, grant);
        assertThat(item(granted, pub("vms", first)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(item(granted, pub("vms", second)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(grantRole(first, requester.getId())).isEqualTo("MEMBER");
        Map<String, Object> detail = auditDetail(pub("vms", first), "vm.access_grant_add");
        assertThat(detail.get("adminIntervention")).isEqualTo(true);
        assertThat(detail.get("batchId")).isEqualTo(granted.get("batchId").asString());
        assertThat(detail.get("granteeUserId")).isEqualTo(requester.getPublicId().toString());
        assertThat(detail.get("role")).isEqualTo("MEMBER");

        assertThat(item(applyJson(orgAdminToken, grant), pub("vms", first))
                .get("result").asString()).isEqualTo("UNCHANGED");
        assertThat(item(applyJson(orgAdminToken, request("VM", targets,
                access("GRANT", requester, "EDITOR"))), pub("vms", first))
                .get("reason").asString()).isEqualTo("ALREADY_GRANTED");

        JsonNode changed = applyJson(sysAdminToken, request("VM", targets,
                access("CHANGE", requester, "EDITOR")));
        assertThat(item(changed, pub("vms", first)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(grantRole(first, requester.getId())).isEqualTo("EDITOR");
        assertThat(auditDetail(pub("vms", first), "vm.access_grant_update").get("previousRole"))
                .isEqualTo("MEMBER");

        JsonNode revoked = applyJson(orgAdminToken, request("VM", targets,
                access("REVOKE", requester, null)));
        assertThat(item(revoked, pub("vms", first)).get("result").asString()).isEqualTo("APPLIED");
        assertThat(grantRole(first, requester.getId())).isNull();
        assertThat(item(applyJson(orgAdminToken, request("VM", targets,
                access("REVOKE", requester, null))), pub("vms", first))
                .get("reason").asString()).isEqualTo("NO_GRANT");

        // The same change reaches any of the four resource types.
        Key key = key(orgA.getId(), workspaceA, "접근 권한 일괄 키", "ACTIVE", null);
        JsonNode keyGrant = applyJson(orgAdminToken, request("LLM_KEY", List.of(key.publicId()),
                access("GRANT", requester, "VIEWER")));
        assertThat(item(keyGrant, key.publicId()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(auditCount(key.publicId(), "llm_key.access_grant_add")).isEqualTo(1);

        mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("VM", targets,
                                access("GRANT", requester, null)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("change.access.role"));
    }

    // ── the call as a whole ────────────────────────────────────────────────

    @Test
    void theSummaryRowCountsEveryResult() throws Exception {
        Key applied = key(orgA.getId(), workspaceA, "요약 적용 키", "ACTIVE", null);
        Key unchanged = key(orgA.getId(), workspaceA, "요약 무변경 키", "ACTIVE", null);
        Key foreign = key(orgB.getId(), workspaceB, "요약 타 기관 키", "ACTIVE", null);
        jdbcTemplate.update("update llm_api_keys set rpm = 90 where public_id = ?",
                unchanged.publicId());
        JsonNode apply = applyJson(orgManagerToken, request("LLM_KEY",
                List.of(applied.publicId(), unchanged.publicId(), foreign.publicId()),
                limits(Map.of("rpm", 90))));

        String json = jdbcTemplate.queryForObject("""
                select detail::text from audit_logs
                 where action = 'admin.bulk_change' and target_id = ?
                """, String.class, apply.get("batchId").asString());
        JsonNode detail = objectMapper.readTree(json);
        assertThat(detail.get("targetType").asString()).isEqualTo("LLM_KEY");
        assertThat(detail.get("kind").asString()).isEqualTo("LLM_KEY_LIMITS");
        assertThat(detail.get("targets").asInt()).isEqualTo(3);
        assertThat(detail.get("counts").get("APPLIED").asInt()).isEqualTo(1);
        assertThat(detail.get("counts").get("UNCHANGED").asInt()).isEqualTo(1);
        assertThat(detail.get("counts").get("SKIPPED").asInt()).isEqualTo(1);
        assertThat(detail.get("counts").get("STALE").asInt()).isZero();
    }

    @Test
    void theRequestShapeIsRefusedBeforeAnyTargetIsLookedAt() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "요청 모양 키", "ACTIVE", null);
        UUID id = key.publicId();

        for (String denied : new String[] {userToken, orgViewerToken}) {
            mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + denied)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    request("LLM_KEY", List.of(id), limits(Map.of("rpm", 90))))))
                    .andExpect(status().isForbidden());
        }

        refused(request("VM", List.of(id), limits(Map.of("rpm", 90))), "targetType");
        refused(request("LLM_KEY", List.of(id, id), limits(Map.of("rpm", 90))), "targetIds");
        refused(request("LLM_KEY", List.of(id), Map.of("kind", "LLM_KEY_LIMITS")),
                "change.llmKeyLimits");
        refused(request("LLM_KEY", List.of(id), Map.of("kind", "LLM_KEY_LIMITS",
                "llmKeyLimits", Map.of("rpm", 90), "vmPower", Map.of("action", "START"))),
                "change.vmPower");
        refused(request("LLM_KEY", List.of(id), limits(Map.of())), "change.llmKeyLimits");
        refused(request("LLM_KEY", List.of(id), Map.of("kind", "LLM_KEY_STATUS",
                "llmKeyStatus", Map.of("action", "SUSPEND"))), "change.llmKeyStatus.reason");
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(UUID.randomUUID());
        }
        refused(request("LLM_KEY", tooMany, limits(Map.of("rpm", 90))), "targetIds");

        // Apply without the preview's fingerprints is refused whole, not per target.
        mockMvc.perform(post(APPLY).header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request("LLM_KEY", List.of(id), limits(Map.of("rpm", 90))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("fingerprints"));
        assertThat(keyRow(key).get("rpm")).isEqualTo(60);
    }

    // ── request helpers ────────────────────────────────────────────────────

    private void refused(Map<String, Object> body, String field) throws Exception {
        mockMvc.perform(post(PREVIEW).header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    private static Map<String, Object> request(String targetType, List<UUID> targetIds,
            Map<String, Object> change) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetIds", targetIds.stream().map(UUID::toString).toList());
        body.put("change", change);
        return body;
    }

    private static Map<String, Object> limits(Map<String, Object> fields) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("kind", "LLM_KEY_LIMITS");
        change.put("llmKeyLimits", fields);
        return change;
    }

    private static Map<String, Object> listOp(String op, List<String> values) {
        return Map.of("op", op, "values", values);
    }

    private static Map<String, Object> access(String action, User grantee, String role) {
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("userId", grantee.getPublicId().toString());
        access.put("action", action);
        if (role != null) {
            access.put("role", role);
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("kind", "ACCESS");
        change.put("access", access);
        return change;
    }

    private JsonNode previewJson(String token, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(perform(PREVIEW, token, body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Previews, hands the fingerprints back, and applies: the console's flow. */
    private JsonNode applyJson(String token, Map<String, Object> body) throws Exception {
        return apply(token, body, fingerprintsOf(previewJson(token, body)));
    }

    private JsonNode apply(String token, Map<String, Object> body,
            Map<String, String> fingerprints) throws Exception {
        Map<String, Object> withFingerprints = new LinkedHashMap<>(body);
        withFingerprints.put("fingerprints", fingerprints);
        return objectMapper.readTree(perform(APPLY, token, withFingerprints)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions perform(String path, String token, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, String> fingerprintsOf(JsonNode preview) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (JsonNode item : preview.get("items")) {
            fingerprints.put(item.get("targetId").asString(), item.get("fingerprint").asString());
        }
        return fingerprints;
    }

    private static JsonNode item(JsonNode response, UUID targetId) {
        for (JsonNode item : response.get("items")) {
            if (item.get("targetId").asString().equals(targetId.toString())) {
                return item;
            }
        }
        throw new AssertionError("no item for " + targetId + " in " + response);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private record Key(long id, UUID publicId) {
    }

    private Key key(long orgId, long workspaceId, String name, String status, Instant expiresAt) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '테스트', ?)
                returning id
                """, Long.class, workspaceId, orgId, requester.getId(), name + " 신청");
        jdbcTemplate.update("insert into llm_key_request_details (request_id) values (?)", requestId);
        long accountId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, '일괄 시험 사업', ?)
                on conflict (org_id, lower(name)) do update set name = excluded.name
                returning id
                """, Long.class, orgId, requester.getId());
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          token_hash, token_prefix, status, expires_at,
                                          rpm, tpm, concurrency, daily_tokens, credit_limit,
                                          openrouter_account_id, created_by, revoked_at)
                values (?, ?, ?, ?, '테스트', ?, ?, ?::llm_api_key_status, ?,
                        60, 1000, 4, 10000, 1.00, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, hash,
                "pickle-" + hash.substring(0, 6), status,
                expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                accountId, requester.getId(),
                "REVOKED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        return new Key(id, pub("llm_api_keys", id));
    }

    private void setLists(Key key, String allowed, String denied, String passthrough) {
        jdbcTemplate.update("update llm_api_keys set credit_allowed_models = ?::jsonb, "
                        + "credit_denied_models = ?::jsonb, passthrough_endpoints = ?::jsonb "
                        + "where id = ?", allowed, denied, passthrough, key.id());
    }

    private Map<String, Object> keyRow(Key key) {
        return jdbcTemplate.queryForMap("select rpm, tpm, concurrency, daily_tokens, "
                + "credit_limit, status::text as status from llm_api_keys where id = ?", key.id());
    }

    private List<String> models(Key key, String column) {
        String json = jdbcTemplate.queryForObject(
                "select " + column + "::text from llm_api_keys where id = ?", String.class, key.id());
        return objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<>() {});
    }

    private long createVm(long vmOrgId, long vmWorkspaceId, String status, LocalDate endDate) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'",
                Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, vmWorkspaceId, vmOrgId,
                requester.getId(), "일괄 변경 테스트", imageId);
        String hostname = "abc-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, vmWorkspaceId, vmOrgId, requestId, hostname, hostname,
                imageId, status, endDate);
    }

    private String grantRole(long vmId, long userId) {
        return jdbcTemplate.query("""
                select role::text from resource_access_grants
                 where resource_type = 'VM' and resource_id = ? and user_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, vmId, userId);
    }

    private Map<String, Object> auditDetail(UUID targetId, String action) {
        String json = jdbcTemplate.queryForObject("""
                select detail::text from audit_logs
                 where target_id = ? and action = ?
                 order by id desc limit 1
                """, String.class, targetId.toString(), action);
        assertThat(json).as("audit detail for %s", action).isNotNull();
        return objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<>() {});
    }

    private long auditCount(UUID targetId, String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where target_id = ? and action = ?",
                Long.class, targetId.toString(), action);
    }

    private long generation() {
        return jdbcTemplate.queryForObject(
                "select coalesce((select generation from llm_gateway_state where id), 0)",
                Long.class);
    }

    private long workspace(String name) {
        return workspaceRepository.save(new Workspace(WorkspaceKind.PROJECT,
                name + " " + UUID.randomUUID().toString().substring(0, 8), null)).getId();
    }

    private Org org(String name) {
        return orgRepository.findFirstByNameOrderByIdAsc(name)
                .orElseGet(() -> orgRepository.save(new Org(name, null)));
    }

    private User user(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(role);
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
            return saved;
        });
    }

    private String token(User user) {
        return jwtService.createAccessToken(user);
    }

    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
