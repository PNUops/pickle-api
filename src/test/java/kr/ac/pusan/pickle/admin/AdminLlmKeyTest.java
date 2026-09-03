package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
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
import tools.jackson.databind.ObjectMapper;

/** Administrator LLM key scope, secret redaction, limits and state flow. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmKeyTest {

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

    private Org orgA;
    private Org orgB;
    private long workspaceA;
    private long workspaceB;
    private User requester;
    private String requesterToken;
    private String orgManagerToken;
    private String orgViewerToken;
    private String orgAdminToken;
    private String sysViewerToken;
    private String sysManagerToken;
    private String sysAdminToken;

    @BeforeEach
    void setUp() {
        orgA = org("LLM 관리자 테스트 기관 A");
        orgB = org("LLM 관리자 테스트 기관 B");
        workspaceA = workspace("LLM 관리자 A");
        workspaceB = workspace("LLM 관리자 B");
        requester = user("adminllm.requester@pusan.ac.kr", "LLM 신청자", UserRole.USER, null);
        requesterToken = token(requester);
        jdbcTemplate.update("insert into workspace_members (workspace_id, user_id, role) "
                        + "values (?, ?, 'OWNER'::workspace_member_role) on conflict do nothing",
                workspaceA, requester.getId());
        jdbcTemplate.update("insert into workspace_members (workspace_id, user_id, role) "
                        + "values (?, ?, 'OWNER'::workspace_member_role) on conflict do nothing",
                workspaceB, requester.getId());
        orgManagerToken = token(user("adminllm.orgmanager@pusan.ac.kr", "기관 운영자",
                UserRole.ORG_MANAGER, orgA.getId()));
        orgViewerToken = token(user("adminllm.orgviewer@pusan.ac.kr", "기관 열람자",
                UserRole.ORG_VIEWER, orgA.getId()));
        User orgAdmin = user("adminllm.orgadmin@pusan.ac.kr", "복수 기관 관리자",
                UserRole.ORG_ADMIN, orgA.getId());
        SeedFixtures.grantOrgRole(jdbcTemplate, orgAdmin.getId(), orgB.getId(),
                UserRole.ORG_VIEWER);
        orgAdminToken = token(orgAdmin);
        sysViewerToken = token(user("adminllm.sysviewer@pusan.ac.kr", "시스템 열람자",
                UserRole.SYS_VIEWER, null));
        sysManagerToken = token(user("adminllm.sysmanager@pusan.ac.kr", "시스템 운영자",
                UserRole.SYS_MANAGER, null));
        sysAdminToken = token(user("adminllm.sysadmin@pusan.ac.kr", "시스템 관리자",
                UserRole.SYS_ADMIN, null));
    }

    @Test
    void listAndDetailAreOrgScopedAndNeverExposeSecrets() throws Exception {
        Key a = key(orgA.getId(), workspaceA, "기관 A 키", "ACTIVE", null);
        key(orgB.getId(), workspaceB, "기관 B 키", "ACTIVE", null);

        String body = mockMvc.perform(get("/api/v1/admin/llm/keys?query=기관 A")
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a.publicId().toString()))
                .andExpect(jsonPath("$.content[0].orgId").value(orgA.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].requestId")
                        .value(a.requestPublicId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(a.tokenHash()).doesNotContain(a.tokenPrefix());

        mockMvc.perform(get("/api/v1/admin/llm/keys?orgId=" + orgB.getPublicId())
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + a.publicId())
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(a.requestPublicId().toString()))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(a.tokenHash()).doesNotContain(a.tokenPrefix()));
    }

    @Test
    void requestIdFilterFindsTheExactKeyAndUnknownIsEmpty() throws Exception {
        Key wanted = key(orgA.getId(), workspaceA, "신청 연결 대상", "ACTIVE", null);
        key(orgA.getId(), workspaceA, "같은 범위의 다른 키", "ACTIVE", null);

        mockMvc.perform(get("/api/v1/admin/llm/keys?requestId=" + wanted.requestPublicId())
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(wanted.publicId().toString()))
                .andExpect(jsonPath("$.content[0].requestId")
                        .value(wanted.requestPublicId().toString()));

        mockMvc.perform(get("/api/v1/admin/llm/keys?requestId=" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void allRolesAndMultiOrgGrantsEnforceReadWriteScope() throws Exception {
        Key a = key(orgA.getId(), workspaceA, "기관 A 역할 키", "ACTIVE", null);
        Key b = key(orgB.getId(), workspaceB, "기관 B 역할 키", "ACTIVE", null);

        mockMvc.perform(get("/api/v1/admin/llm/keys/" + a.publicId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + a.publicId())
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + a.publicId())
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + a.publicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + b.publicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + b.publicId())
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + b.publicId())
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/llm/keys/" + b.publicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());

        putLimits(b.publicId(), orgAdminToken, limits(70, "1.00"))
                .andExpect(status().isNotFound());
        postJson("/api/v1/admin/llm/keys/" + b.publicId() + "/suspend",
                orgAdminToken, Map.of("reason", "기관 B 쓰기 거부 확인"))
                .andExpect(status().isNotFound());
        putLimits(a.publicId(), orgAdminToken, limits(70, "1.00"))
                .andExpect(status().isOk());
        putLimits(b.publicId(), sysViewerToken, limits(70, "1.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void limitsAndStatusFollowRoleAndStateRules() throws Exception {
        Key active = key(orgA.getId(), workspaceA, "운영 키", "ACTIVE", null);
        long generation = generation();

        putLimits(active.publicId(), orgViewerToken, limits(90, "1.00"))
                .andExpect(status().isForbidden());
        putLimits(active.publicId(), sysManagerToken, limits(90, "1.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpm").value(90));
        assertThat(generation()).isEqualTo(generation + 1);

        putLimits(active.publicId(), sysManagerToken, limits(100, "2.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        putLimits(active.publicId(), orgManagerToken, limits(100, "2.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value(2.00));

        postJson("/api/v1/admin/llm/keys/" + active.publicId() + "/suspend",
                orgManagerToken, Map.of("reason", "운영 점검"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mockMvc.perform(post("/api/v1/admin/llm/keys/" + active.publicId() + "/resume")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Key expired = key(orgA.getId(), workspaceA, "만료 키", "ACTIVE", Instant.now().minusSeconds(60));
        putLimits(expired.publicId(), sysAdminToken, limits(120, "1.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LLM_KEY_INVALID_STATE"));
        postJson("/api/v1/admin/llm/keys/" + expired.publicId() + "/suspend",
                sysAdminToken, Map.of("reason", "불가"))
                .andExpect(status().isConflict());

        Key pending = key(orgA.getId(), workspaceA, "발급 전 키", "PENDING", null);
        putLimits(pending.publicId(), orgManagerToken, limits(80, "0"))
                .andExpect(status().isOk());
        postJson("/api/v1/admin/llm/keys/" + pending.publicId() + "/suspend",
                orgManagerToken, Map.of("reason", "불가"))
                .andExpect(status().isConflict());

        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where target_id = ? and action in "
                        + "('llm_key.limits_update', 'llm_key.suspend', 'llm_key.resume')",
                Long.class, active.publicId().toString());
        assertThat(audits).isEqualTo(4);
    }

    @Test
    void fullReplacementRequiresEveryLimitProperty() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "완전 교체 키", "ACTIVE", null);
        putLimits(key.publicId(), sysAdminToken, Map.of("rpm", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("creditLimit"));
        putLimits(key.publicId(), sysAdminToken, Map.of("rpm", 10, "creditLimit", 0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("limits"));
    }

    // The money-axis allow list joins the full replacement, so a request that
    // omits it replaces nothing — the same rule the other six already follow.
    @Test
    void creditAllowlistIsPartOfTheFullReplacement() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "허용 목록 키", "ACTIVE", null);
        Map<String, Object> withoutList = limits(60, "5.00");
        withoutList.remove("creditAllowedModels");
        putLimits(key.publicId(), sysAdminToken, withoutList)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("limits"));

        // Stored normalized: lower-cased, trimmed, duplicates gone, order kept.
        Map<String, Object> messy = limits(60, "5.00");
        messy.put("creditAllowedModels",
                java.util.List.of("OpenAI/*", " anthropic/claude-sonnet-4 ", "openai/*"));
        putLimits(key.publicId(), sysAdminToken, messy)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAllowedModels.length()").value(2))
                .andExpect(jsonPath("$.creditAllowedModels[0]").value("openai/*"))
                .andExpect(jsonPath("$.creditAllowedModels[1]").value("anthropic/claude-sonnet-4"));

        // null is unrestricted here, unlike creditLimit where null is refused.
        Map<String, Object> cleared = limits(60, "5.00");
        cleared.put("creditAllowedModels", null);
        putLimits(key.publicId(), sysAdminToken, cleared)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAllowedModels.length()").value(0));
    }

    @Test
    void creditAllowlistRefusesUnusableEntriesAndNeedsMoney() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "허용 목록 검증 키", "ACTIVE", null);
        // A bare star would be a second spelling of "unrestricted".
        Map<String, Object> star = limits(60, "5.00");
        star.put("creditAllowedModels", java.util.List.of("*"));
        putLimits(key.publicId(), sysAdminToken, star)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("creditAllowedModels[0]"));

        // Self-serving names are not commercial names; listing one reads as
        // opening something this list cannot open.
        Map<String, Object> reserved = limits(60, "5.00");
        reserved.put("creditAllowedModels", java.util.List.of("pickle-general"));
        putLimits(key.publicId(), sysAdminToken, reserved)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("creditAllowedModels[0]"));

        // A fence around a budget of zero fences nothing.
        Map<String, Object> noMoney = limits(60, "0");
        noMoney.put("creditAllowedModels", java.util.List.of("openai/*"));
        putLimits(key.publicId(), sysAdminToken, noMoney)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("creditLimit"));
    }

    // The list decides what the money may be spent on, so it sits on the money
    // side of the system-operator gate. Left off it, a SYS_MANAGER could open
    // every vendor on a restricted key without touching a single number.
    @Test
    void systemManagerCannotChangeTheCreditAllowlist() throws Exception {
        Key key = key(orgA.getId(), workspaceA, "운영자 게이트 키", "ACTIVE", null);
        Map<String, Object> widened = limits(60, "1.00");
        widened.put("creditAllowedModels", java.util.List.of("openai/*"));
        putLimits(key.publicId(), sysManagerToken, widened)
                .andExpect(status().isForbidden());

        // The same request without a list change stays allowed for that role.
        putLimits(key.publicId(), sysManagerToken, limits(60, "1.00"))
                .andExpect(status().isOk());
    }

    /**
     * 축은 한도와 다르다. 빈 한도는 서비스 기본값이라는 뜻이지만 빈 축은 무엇을 달라는
     * 것인지 말하지 않은 것이다. 금액은 유료 축을 켠 신청만 담을 수 있다.
     */
    @Test
    void theRequestSaysWhichAxesItIsForAndOnlyThenCarriesAnAmount() throws Exception {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("type", "LLM_API_KEY");
        base.put("workspaceId", pub("workspaces", workspaceA));
        base.put("orgId", orgA.getPublicId());
        base.put("purpose", "축 검증");
        base.put("reqEndDate", LocalDate.now(ClockConfig.KST).plusMonths(4).toString());
        base.put("displayName", "axis-key");

        Map<String, Object> noAxis = new java.util.LinkedHashMap<>(base);
        noAxis.put("llmKey", Map.of("useCampusModels", false, "useCommercialModels", false));
        postJson("/api/v1/requests", requesterToken, noAxis)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("llmKey.useCampusModels"));

        Map<String, Object> amountWithoutAxis = new java.util.LinkedHashMap<>(base);
        amountWithoutAxis.put("llmKey",
                Map.of("useCommercialModels", false, "reqCreditLimit", 20));
        postJson("/api/v1/requests", requesterToken, amountWithoutAxis)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("llmKey.reqCreditLimit"));

        Map<String, Object> both = new java.util.LinkedHashMap<>(base);
        both.put("llmKey", Map.of("useCampusModels", true, "useCommercialModels", true,
                "reqCreditLimit", 20));
        postJson("/api/v1/requests", requesterToken, both)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.llmKey.useCommercialModels").value(true))
                .andExpect(jsonPath("$.llmKey.reqCreditLimit").value(20.00));
    }

    @Test
    void requestApprovalIssueAndAdminLifecycleWorkAsOneFlow() throws Exception {
        Map<String, Object> create = new java.util.LinkedHashMap<>();
        create.put("type", "LLM_API_KEY");
        create.put("workspaceId", pub("workspaces", workspaceA));
        create.put("orgId", orgA.getPublicId());
        create.put("purpose", "관리자 vertical flow");
        create.put("reqEndDate", LocalDate.now(ClockConfig.KST).plusMonths(4).toString());
        create.put("displayName", "vertical-flow-key");
        // 축을 비우면 자체 서빙 모델만 쓰는 보통의 신청이다.
        create.put("llmKey", Map.of("usagePlan", "통합 검증", "reqRpm", 30,
                "reqTpm", 3000, "reqDailyTokens", 30000));
        String created = postJson("/api/v1/requests", requesterToken, create)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LLM_API_KEY"))
                .andExpect(jsonPath("$.llmKey.useCampusModels").value(true))
                .andExpect(jsonPath("$.llmKey.useCommercialModels").value(false))
                .andExpect(jsonPath("$.llmKey.reqCreditLimit").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(created).get("id").asString());

        Map<String, Object> grant = new java.util.LinkedHashMap<>();
        grant.put("grantedRpm", 40);
        grant.put("grantedTpm", 4000);
        grant.put("grantedConcurrency", 2);
        grant.put("grantedDailyTokens", 40000);
        grant.put("grantedCreditLimit", "0");
        grant.put("grantedCreditLimitReset", null);
        postJson("/api/v1/admin/requests/" + requestId + "/approve", orgManagerToken,
                Map.of("llmKey", grant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String listed = mockMvc.perform(get("/api/v1/admin/llm/keys?query=vertical-flow-key")
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].requestId").value(requestId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID keyId = UUID.fromString(objectMapper.readTree(listed)
                .get("content").get(0).get("id").asString());

        String issued = mockMvc.perform(post("/api/v1/llm-keys/" + keyId + "/token")
                        .header("Authorization", "Bearer " + requesterToken)
                        .header(ReauthTestSupport.HEADER,
                                ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService,
                                        requesterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String plaintext = objectMapper.readTree(issued).get("token").asString();

        putLimits(keyId, orgManagerToken, limits(50, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpm").value(50));
        assertThat(jdbcTemplate.queryForObject(
                "select granted_rpm from llm_key_request_details where request_id = ?",
                Integer.class, SeedFixtures.internalId(jdbcTemplate, "requests", requestId)))
                .isEqualTo(40);
        postJson("/api/v1/admin/llm/keys/" + keyId + "/suspend", orgManagerToken,
                Map.of("reason", "통합 점검"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mockMvc.perform(post("/api/v1/admin/llm/keys/" + keyId + "/resume")
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/llm-keys/" + keyId + "/revoke")
                        .header("Authorization", "Bearer " + requesterToken)
                        .header(ReauthTestSupport.HEADER,
                                ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService,
                                        requesterToken)))
                .andExpect(status().isNoContent());

        String detail = mockMvc.perform(get("/api/v1/admin/llm/keys/" + keyId)
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(plaintext);
    }

    @Test
    void llmApprovalContextUsesTheLlmContributorAndTypedHistory() throws Exception {
        key(orgA.getId(), workspaceA, "기존 키", "ACTIVE", null);
        long historical = request(orgA.getId(), workspaceA, "이전 LLM 신청");
        long current = request(orgA.getId(), workspaceA, "현재 LLM 신청");

        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", current) + "/context")
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LLM_API_KEY"))
                .andExpect(jsonPath("$.vm").value((Object) null))
                .andExpect(jsonPath("$.llmKey.applicantKeys[0].name").value("기존 키"))
                .andExpect(jsonPath("$.llmKey.workspaceKeys[0].creditLimit").value(1.00))
                .andExpect(jsonPath("$.history[?(@.requestId == '%s')].type"
                        .formatted(pub("requests", historical))).value("LLM_API_KEY"))
                .andExpect(jsonPath("$.history[?(@.requestId == '%s')].resourceName"
                        .formatted(pub("requests", historical))).value("이전 LLM 신청"));
    }

    @Test
    void vmApprovalContextUsesTheVmContributorAndMirrorPayload() throws Exception {
        long requestId = vmRequest(orgA.getId(), workspaceA, "VM 승인 context");

        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", requestId) + "/context")
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("VM"))
                .andExpect(jsonPath("$.vm").isNotEmpty())
                .andExpect(jsonPath("$.llmKey").value((Object) null));
    }

    private org.springframework.test.web.servlet.ResultActions putLimits(UUID keyId, String token,
            Map<String, ?> body) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/llm/keys/" + keyId + "/limits")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String uri, String token,
            Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> limits(int rpm, String creditLimit) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("rpm", rpm);
        values.put("tpm", 1000);
        values.put("concurrency", 4);
        values.put("dailyTokens", 10000);
        values.put("creditLimit", creditLimit);
        values.put("creditLimitReset", null);
        values.put("creditAllowedModels", java.util.List.of());
        return values;
    }

    /** One funded account per org, since a money budget must name one. */
    private long openrouterAccount(long orgId) {
        return jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                on conflict (org_id, lower(name)) do update set name = excluded.name
                returning id
                """, Long.class, orgId, "키 시험 사업", requester.getId());
    }

    private Key key(long orgId, long workspaceId, String name, String status, Instant expiresAt) {
        long requestId = request(orgId, workspaceId, name + " 신청");
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        String prefix = "pickle-" + hash.substring(0, 6);
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          token_hash, token_prefix, status, expires_at,
                                          rpm, tpm, concurrency, daily_tokens, credit_limit,
                                          openrouter_account_id, created_by)
                values (?, ?, ?, ?, '테스트', ?, ?, ?::llm_api_key_status, ?,
                        60, 1000, 4, 10000, 1.00, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, name,
                "PENDING".equals(status) ? null : hash,
                "PENDING".equals(status) ? null : prefix, status,
                expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                openrouterAccount(orgId), requester.getId());
        return new Key(pub("llm_api_keys", id), pub("requests", requestId), hash, prefix);
    }

    private long request(long orgId, long workspaceId, String displayName) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '테스트', ?)
                returning id
                """, Long.class, workspaceId, orgId, requester.getId(), displayName);
        jdbcTemplate.update("insert into llm_key_request_details (request_id) values (?)", requestId);
        return requestId;
    }

    private long vmRequest(long orgId, long workspaceId, String displayName) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('VM', ?, ?, ?, '테스트', ?)
                returning id
                """, Long.class, workspaceId, orgId, requester.getId(), displayName);
        Long imageId = jdbcTemplate.queryForObject(
                "select id from os_images order by id limit 1", Long.class);
        jdbcTemplate.update("""
                insert into vm_request_details (request_id, image_id, req_vcpu,
                                                req_memory_mb, req_disk_gb)
                values (?, ?, 2, 2048, 20)
                """, requestId, imageId);
        return requestId;
    }

    private long workspace(String name) {
        return workspaceRepository.save(new Workspace(WorkspaceKind.TEAM,
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

    private long generation() {
        return jdbcTemplate.queryForObject(
                "select coalesce((select generation from llm_gateway_state where id), 0)",
                Long.class);
    }

    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }

    private record Key(UUID publicId, UUID requestPublicId, String tokenHash, String tokenPrefix) {
    }
}
