package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.LlmUsageRollupService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Scope, exact aggregation and redaction semantics for administrator LLM usage. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmUsageTest {

    @Autowired
    private MockMvc mockMvc;
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
    private LlmUsageRollupService rollupService;

    private Org orgA;
    private Org orgB;
    private Workspace workspaceA1;
    private Workspace workspaceA2;
    private Workspace workspaceB;
    private User owner;
    private String tokenModel;
    private String orgViewerToken;
    private String orgManagerToken;
    private String sysViewerToken;
    private String multiOrgToken;
    private String userToken;
    private final List<String> adminTokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_usage_events");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        orgA = org("사용량 기관 A " + suffix);
        orgB = org("사용량 기관 B " + suffix);
        workspaceA1 = workspace("사용량 A1 " + suffix);
        workspaceA2 = workspace("사용량 A2 " + suffix);
        workspaceB = workspace("사용량 B " + suffix);
        owner = user("usage-owner-" + suffix + "@pusan.ac.kr", "사용량 소유자",
                UserRole.USER, null);
        tokenModel = "usage-token-" + suffix;
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model, budget_axis)
                values (?, 'usage-test', 'usage-test-model', 'TOKEN')
                """, tokenModel);

        orgViewerToken = token(user("usage-viewer-" + suffix + "@pusan.ac.kr", "기관 열람자",
                UserRole.ORG_VIEWER, orgA.getId()));
        orgManagerToken = token(user("usage-manager-" + suffix + "@pusan.ac.kr", "기관 운영자",
                UserRole.ORG_MANAGER, orgA.getId()));
        String orgAdminToken = token(user("usage-admin-" + suffix + "@pusan.ac.kr", "기관 관리자",
                UserRole.ORG_ADMIN, orgA.getId()));
        sysViewerToken = token(user("usage-sys-viewer-" + suffix + "@pusan.ac.kr",
                "시스템 열람자", UserRole.SYS_VIEWER, null));
        String sysManagerToken = token(user("usage-sys-manager-" + suffix + "@pusan.ac.kr",
                "시스템 운영자", UserRole.SYS_MANAGER, null));
        String sysAdminToken = token(user("usage-sys-admin-" + suffix + "@pusan.ac.kr",
                "시스템 관리자", UserRole.SYS_ADMIN, null));
        adminTokens.clear();
        adminTokens.addAll(List.of(orgViewerToken, orgManagerToken, orgAdminToken,
                sysViewerToken, sysManagerToken, sysAdminToken));
        User multi = user("usage-multi-" + suffix + "@pusan.ac.kr", "복수 기관 운영자",
                UserRole.ORG_MANAGER, orgA.getId());
        SeedFixtures.grantOrgRole(jdbcTemplate, multi.getId(), orgB.getId(),
                UserRole.ORG_MANAGER);
        multiOrgToken = token(multi);
        userToken = token(owner);
        reportGateway();
    }

    @Test
    void sixAdminRolesReadWhileUserAndUnsupportedDaysAreRejected() throws Exception {
        for (String adminToken : adminTokens) {
            usage(adminToken, "orgId=" + orgA.getPublicId())
                    .andExpect(status().isOk());
        }
        usage(userToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isForbidden());
        usage(orgViewerToken, "orgId=" + orgA.getPublicId() + "&days=8")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("days"));
    }

    @Test
    void fixedWindowsAxisCoverageAndQualityUseKstRollups() throws Exception {
        Key key = key(orgA, workspaceA1, "수요 키", 100L, "10", false,
                account(orgA, "수요 사업"), "2", "8", true);
        Instant today = atKst(LocalDate.now(ClockConfig.KST), 12);
        event(key.id(), tokenModel, "TOKEN", null, 10, 20, false, today);
        event(key.id(), "vendor/model", "CREDIT", null, 4, 6, true,
                today.plusSeconds(10));
        event(key.id(), null, null, null, 1, 1, false, today.plusSeconds(20));
        event(key.id(), tokenModel, "TOKEN", null, 2, 3, false,
                atKst(LocalDate.now(ClockConfig.KST).minusDays(8), 12));
        event(key.id(), tokenModel, "TOKEN", null, 5, 5, false,
                atKst(LocalDate.now(ClockConfig.KST).minusDays(31), 12));
        rollupService.refresh();

        usage(orgViewerToken, "orgId=" + orgA.getPublicId() + "&days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.demand.windows[0].days").value(7))
                .andExpect(jsonPath("$.demand.windows[0].requests").value(3))
                .andExpect(jsonPath("$.demand.windows[0].inputTokens").value(15))
                .andExpect(jsonPath("$.demand.windows[0].outputTokens").value(27))
                .andExpect(jsonPath("$.demand.windows[0].estimatedRequests").value(1))
                .andExpect(jsonPath("$.demand.windows[0].tokenAxisRequests").value(1))
                .andExpect(jsonPath("$.demand.windows[0].creditAxisRequests").value(1))
                .andExpect(jsonPath("$.demand.windows[0].unknownAxisRequests").value(1))
                .andExpect(jsonPath("$.demand.windows[0].axisCoverage").value(0.666667))
                .andExpect(jsonPath("$.demand.windows[1].requests").value(4))
                .andExpect(jsonPath("$.demand.windows[2].requests").value(5))
                .andExpect(jsonPath("$.demand.daily.length()").value(7))
                .andExpect(jsonPath("$.quality.rollupLastSuccessAt").isNotEmpty())
                .andExpect(jsonPath("$.quality.latestUsageReceivedAt").isNotEmpty())
                .andExpect(jsonPath("$.quality.creditMetersTotal").value(1))
                .andExpect(jsonPath("$.quality.creditMetersObserved").value(1))
                .andExpect(jsonPath("$.quality.totalRequests").value(3))
                .andExpect(jsonPath("$.quality.estimatedRequests").value(1))
                .andExpect(jsonPath("$.quality.estimatedRequestRatio").value(0.333333))
                .andExpect(jsonPath("$.quality.totalTokens").value(42))
                .andExpect(jsonPath("$.quality.estimatedTokens").value(10))
                .andExpect(jsonPath("$.quality.estimatedTokenRatio").value(0.238095))
                .andExpect(jsonPath("$.quality.gatewayReportState").value("FRESH"))
                .andExpect(jsonPath("$.quality.usageQueueReportState").value("FRESH"))
                .andExpect(jsonPath("$.quality.lastUsageShipSuccessAt").doesNotExist())
                .andExpect(jsonPath("$.quality.usageQueueObservedAt").doesNotExist())
                .andExpect(jsonPath("$.quality.oldestUnshippedEventAt").doesNotExist())
                .andExpect(jsonPath("$.quality.queuedUsageEvents").doesNotExist())
                .andExpect(jsonPath("$.quality.queuedUsageBytes").doesNotExist())
                .andExpect(jsonPath("$.quality.spoolWriteFailures").doesNotExist());

        usage(sysViewerToken, "orgId=" + orgA.getPublicId() + "&days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality.queuedUsageEvents").value(3))
                .andExpect(jsonPath("$.quality.queuedUsageBytes").value(300))
                .andExpect(jsonPath("$.quality.usageShipFailures").value(4))
                .andExpect(jsonPath("$.quality.unattributedRequests").doesNotExist());
    }

    @Test
    void consumerDrillDownTopAndMultiOrgScopeAreStable() throws Exception {
        Key a1 = key(orgA, workspaceA1, "A1 키", null, "0", false, null,
                null, null, false);
        Key a2 = key(orgA, workspaceA2, "A2 키", null, "0", false, null,
                null, null, false);
        Key b = key(orgB, workspaceB, "B 키", null, "0", false, null,
                null, null, false);
        Instant today = atKst(LocalDate.now(ClockConfig.KST), 12);
        event(a1.id(), tokenModel, "TOKEN", null, 20, 20, false, today);
        event(a2.id(), tokenModel, "TOKEN", null, 10, 10, false, today);
        event(b.id(), tokenModel, "TOKEN", null, 5, 5, false, today);
        rollupService.refresh();

        usage(sysViewerToken, "days=7&top=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("ORG"))
                .andExpect(jsonPath("$.consumers.items.length()").value(1))
                .andExpect(jsonPath("$.consumers.totalItems").value(2))
                .andExpect(jsonPath("$.consumers.truncated").value(true));

        usage(orgViewerToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("WORKSPACE"))
                .andExpect(jsonPath("$.consumers.totalItems").value(2));
        usage(orgViewerToken, "orgId=" + orgA.getPublicId()
                        + "&workspaceId=" + workspaceA1.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("KEY"))
                .andExpect(jsonPath("$.consumers.items[0].keyId")
                        .value(a1.publicId().toString()));
        usage(multiOrgToken, "days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("WORKSPACE"))
                .andExpect(jsonPath("$.consumers.totalItems").value(3));
        usage(multiOrgToken, "orgId=" + orgB.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.totalItems").value(1));
    }

    @Test
    void workspaceConsumersMergeTheSameWorkspaceAcrossReadableOrganisations() throws Exception {
        Key a = key(orgA, workspaceA1, "공유 workspace A 키", null, "0", false,
                null, null, null, false);
        Key b = key(orgB, workspaceA1, "공유 workspace B 키", null, "0", false,
                null, null, null, false);
        Instant today = atKst(LocalDate.now(ClockConfig.KST), 12);
        event(a.id(), tokenModel, "TOKEN", null, 10, 10, false, today);
        event(b.id(), tokenModel, "TOKEN", null, 20, 20, false, today.plusSeconds(1));
        rollupService.refresh();

        usage(multiOrgToken, "days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("WORKSPACE"))
                .andExpect(jsonPath("$.consumers.totalItems").value(1))
                .andExpect(jsonPath("$.consumers.items[0].workspaceId")
                        .value(workspaceA1.getPublicId().toString()))
                .andExpect(jsonPath("$.consumers.items[0].orgId").doesNotExist())
                .andExpect(jsonPath("$.consumers.items[0].orgName").doesNotExist())
                .andExpect(jsonPath("$.consumers.items[0].requests").value(2))
                .andExpect(jsonPath("$.consumers.items[0].inputTokens").value(30));

        usage(orgViewerToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.totalItems").value(1))
                .andExpect(jsonPath("$.consumers.items[0].requests").value(1));
    }

    @Test
    void crossOrgAndUnknownFiltersFollowMaskedScopeRules() throws Exception {
        key(orgA, workspaceA1, "A 키", null, "0", false, null, null, null, false);
        key(orgB, workspaceB, "B 키", null, "0", false, null, null, null, false);

        usage(orgViewerToken, "orgId=" + orgB.getPublicId())
                .andExpect(status().isNotFound());
        usage(orgViewerToken, "workspaceId=" + workspaceB.getPublicId())
                .andExpect(status().isNotFound());
        usage(orgViewerToken, "orgId=" + orgA.getPublicId()
                        + "&workspaceId=" + workspaceB.getPublicId())
                .andExpect(status().isNotFound());
        usage(sysViewerToken, "orgId=" + orgA.getPublicId()
                        + "&workspaceId=" + workspaceB.getPublicId())
                .andExpect(status().isNotFound());
        usage(sysViewerToken, "orgId=" + SeedFixtures.UNKNOWN_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demand.windows[0].requests").value(0))
                .andExpect(jsonPath("$.consumers.totalItems").value(0));
        usage(sysViewerToken, "workspaceId=" + SeedFixtures.UNKNOWN_ID)
                .andExpect(status().isNotFound());

        Workspace empty = workspace("사용량 empty " + UUID.randomUUID());
        usage(orgViewerToken, "workspaceId=" + empty.getPublicId())
                .andExpect(status().isNotFound());
        usage(sysViewerToken, "workspaceId=" + empty.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("KEY"))
                .andExpect(jsonPath("$.consumers.totalItems").value(0));

        Workspace otherVmOnly = workspace("사용량 VM-only B " + UUID.randomUUID());
        vmAssociation(orgB, otherVmOnly, workspaceB, "RUNNING");
        usage(orgViewerToken, "workspaceId=" + otherVmOnly.getPublicId())
                .andExpect(status().isNotFound());

        Workspace visibleVmOnly = workspace("사용량 VM-only A " + UUID.randomUUID());
        vmAssociation(orgA, visibleVmOnly, workspaceA2, "RUNNING");
        usage(orgViewerToken, "workspaceId=" + visibleVmOnly.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.totalItems").value(0));
    }

    @Test
    void limitReviewUsesOnlyExactReasonsAndCarriesAccountDeepLink() throws Exception {
        Account account = account(orgA, "사업 계정");
        Key pressured = key(orgA, workspaceA1, "압력 키", 100L, "10", true, account,
                "5", "5", true);
        Key disconnected = key(orgA, workspaceA2, "미연결 키", null, "20", false, account,
                null, null, false);
        key(orgA, workspaceA2, "한도 없음", null, "0", false, null,
                null, null, false);
        jdbcTemplate.update("update llm_api_keys set quota_exhausted = true where id = ?",
                pressured.id());
        Instant today = atKst(LocalDate.now(ClockConfig.KST), 12);
        event(pressured.id(), tokenModel, "TOKEN", null, 30, 30, false, today);
        for (String reason : List.of("quota_exhausted", "credit_exhausted",
                "rate_limit_requests", "rate_limit_tokens", "rate_limit_concurrency",
                "server_busy")) {
            event(pressured.id(), tokenModel, "TOKEN", reason, 0, 0, false,
                    today.plusSeconds(Math.abs(reason.hashCode() % 1000)));
        }
        event(pressured.id(), tokenModel, "TOKEN", null, 0, 0, false,
                today.plusSeconds(2000));
        jdbcTemplate.update("""
                insert into llm_usage_events
                       (event_id, key_id, public_model_name, budget_axis, status,
                        input_tokens, output_tokens, estimated, latency_ms, requested_at)
                values (?, ?, ?, 'TOKEN', 'RATE_LIMITED', 0, 0, false, 1, ?)
                """, UUID.randomUUID().toString(), pressured.id(), tokenModel,
                Timestamp.from(today.plusSeconds(3000)));
        event(pressured.id(), tokenModel, null, null, 7, 3, false,
                today.plusSeconds(4000));
        jdbcTemplate.update("update llm_models set budget_axis = 'CREDIT' where public_name = ?",
                tokenModel);
        rollupService.refresh();

        String body = usage(orgManagerToken, "orgId=" + orgA.getPublicId() + "&top=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitReview.totalItems").value(2))
                .andExpect(jsonPath("$.limitReview.truncated").value(true))
                .andExpect(jsonPath("$.limitReview.items[0].keyId")
                        .value(pressured.publicId().toString()))
                .andExpect(jsonPath("$.limitReview.items[0].todayTokens").value(60))
                .andExpect(jsonPath("$.limitReview.items[0].todayUnknownAxisTokens").value(10))
                .andExpect(jsonPath("$.limitReview.items[0].quotaExhausted").value(true))
                .andExpect(jsonPath("$.limitReview.items[0].creditUsage").value(5))
                .andExpect(jsonPath("$.limitReview.items[0].creditLimitRemaining").value(5))
                .andExpect(jsonPath("$.limitReview.items[0].openrouterAccountId")
                        .value(account.publicId().toString()))
                .andExpect(jsonPath("$.limitReview.items[0].openrouterAccountName")
                        .value(account.name()))
                .andExpect(jsonPath("$.limitReview.items[0].pressure.length()").value(5))
                .andExpect(jsonPath("$.limitReview.items[0].pressure[0].reason")
                        .value("quota_exhausted"))
                .andExpect(jsonPath("$.limitReview.items[0].pressure[1].reason")
                        .value("credit_exhausted"))
                .andExpect(jsonPath("$.limitReview.items[0].pressure[2].reason")
                        .value("rate_limit_requests"))
                .andExpect(jsonPath("$.limitReview.items[0].pressure[3].reason")
                        .value("rate_limit_tokens"))
                .andExpect(jsonPath("$.limitReview.items[0].pressure[4].reason")
                        .value("rate_limit_concurrency"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("server_busy");
    }

    @Test
    void limitReviewExcludesRevokedAndExpiredBeforeTopAndTotal() throws Exception {
        Key active = key(orgA, workspaceA1, "active review", 100L, "0", false,
                null, null, null, false);
        Key suspended = key(orgA, workspaceA1, "suspended review", 100L, "0", false,
                null, null, null, false);
        Key pending = key(orgA, workspaceA1, "pending review", 100L, "0", false,
                null, null, null, false);
        Key revoked = key(orgA, workspaceA1, "revoked review", 100L, "0", false,
                null, null, null, false);
        Key expired = key(orgA, workspaceA1, "expired review", 100L, "0", false,
                null, null, null, false);
        jdbcTemplate.update("update llm_api_keys set status = 'SUSPENDED' where id = ?",
                suspended.id());
        jdbcTemplate.update("update llm_api_keys set status = 'PENDING' where id = ?",
                pending.id());
        jdbcTemplate.update("update llm_api_keys set status = 'REVOKED' where id = ?",
                revoked.id());
        jdbcTemplate.update("update llm_api_keys set expires_at = now() - interval '1 minute' "
                + "where id = ?", expired.id());

        String body = usage(orgViewerToken, "orgId=" + orgA.getPublicId() + "&top=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitReview.totalItems").value(3))
                .andExpect(jsonPath("$.limitReview.truncated").value(false))
                .andExpect(jsonPath("$.limitReview.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(active.publicId().toString())
                .contains(suspended.publicId().toString())
                .contains(pending.publicId().toString())
                .doesNotContain(revoked.publicId().toString())
                .doesNotContain(expired.publicId().toString());
    }

    @Test
    void unattributedAndModellessUsageIsSysGlobalQualityOnly() throws Exception {
        event(null, null, null, null, 0, 0, false,
                atKst(LocalDate.now(ClockConfig.KST), 12));
        rollupService.refresh();

        usage(sysViewerToken, "days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demand.windows[0].requests").value(1))
                .andExpect(jsonPath("$.demand.windows[0].unknownAxisRequests").value(1))
                .andExpect(jsonPath("$.consumers.totalItems").value(0))
                .andExpect(jsonPath("$.quality.unattributedRequests").value(1))
                .andExpect(jsonPath("$.quality.queuedUsageEvents").value(3))
                .andExpect(jsonPath("$.quality.spoolWriteFailures").value(2));
        usage(orgViewerToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demand.windows[0].requests").value(0))
                .andExpect(jsonPath("$.quality.unattributedRequests").doesNotExist());
    }

    private ResultActions usage(String token, String query) throws Exception {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return mockMvc.perform(get("/api/v1/admin/llm/usage" + suffix)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private Key key(Org org, Workspace workspace, String name, Long dailyTokens,
            String creditLimit, boolean connected, Account account, String creditUsage,
            String creditRemaining, boolean observed) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '사용량 시험', ?)
                returning id
                """, Long.class, workspace.getId(), org.getId(), owner.getId(), name + " 신청");
        jdbcTemplate.update(
                "insert into llm_key_request_details (request_id) values (?)", requestId);
        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Long accountId = account == null ? null : account.id();
        String remoteHash = connected ? "or-" + UUID.randomUUID() : null;
        String remoteEnc = connected ? "enc-test-" + UUID.randomUUID() : null;
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, token_hash, token_prefix,
                        status, daily_tokens, credit_limit, quota_exhausted,
                        openrouter_account_id,
                        openrouter_key_hash, openrouter_key_enc,
                        openrouter_usage, openrouter_limit_remaining, openrouter_usage_at,
                        created_by)
                values (?, ?, ?, ?, ?, 'pickle-test', 'ACTIVE', ?, ?::numeric, false,
                        ?, ?, ?, ?::numeric, ?::numeric, ?, ?)
                returning public_id
                """, UUID.class, workspace.getId(), org.getId(), requestId, name, hash,
                dailyTokens, creditLimit, accountId,
                remoteHash, remoteEnc, creditUsage, creditRemaining,
                observed ? Timestamp.from(Instant.now()) : null, owner.getId());
        long id = SeedFixtures.internalId(jdbcTemplate, "llm_api_keys", publicId);
        return new Key(id, publicId);
    }

    private Account account(Org org, String name) {
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?) returning public_id
                """, UUID.class, org.getId(), name, owner.getId());
        return new Account(SeedFixtures.internalId(
                jdbcTemplate, "openrouter_accounts", publicId), publicId, name);
    }

    private void vmAssociation(Org org, Workspace targetWorkspace,
            Workspace requestWorkspace, String status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('VM', ?, ?, ?, 'workspace association', 'association request')
                returning id
                """, Long.class, requestWorkspace.getId(), org.getId(), owner.getId());
        long nodeId = jdbcTemplate.queryForObject(
                "select id from nodes order by id limit 1", Long.class);
        long imageId = jdbcTemplate.queryForObject(
                "select id from os_images order by id limit 1", Long.class);
        String hostname = "usage-association-" + UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status)
                """, nodeId, targetWorkspace.getId(), org.getId(), requestId,
                hostname, hostname, imageId, status);
    }

    private void event(Long keyId, String model, String axis, String errorType,
            int inputTokens, int outputTokens, boolean estimated, Instant requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events
                       (event_id, key_id, public_model_name, budget_axis, status, error_type,
                        input_tokens, output_tokens, estimated, latency_ms, requested_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, UUID.randomUUID().toString(), keyId, model, axis,
                errorType == null ? "OK" : "RATE_LIMITED", errorType,
                inputTokens, outputTokens, estimated, Timestamp.from(requestedAt));
    }

    private void reportGateway() {
        jdbcTemplate.update("""
                insert into llm_gateway_state
                       (id, generation, last_contact_at, last_usage_ship_success_at,
                        oldest_unshipped_event_at, queued_usage_events, queued_usage_bytes,
                        usage_queue_observed_at, usage_queue_scan_failures,
                        spool_write_failures, usage_ship_failures)
                values (true, 1, now(), now(), now() - interval '1 minute',
                        3, 300, now(), 1, 2, 4)
                on conflict (id) do update set last_contact_at = now(),
                    last_usage_ship_success_at = now(),
                    oldest_unshipped_event_at = now() - interval '1 minute',
                    queued_usage_events = 3, queued_usage_bytes = 300,
                    usage_queue_observed_at = now(), usage_queue_scan_failures = 1,
                    spool_write_failures = 2, usage_ship_failures = 4
                """);
    }

    private Instant atKst(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    }

    private Org org(String name) {
        return orgRepository.save(new Org(name, null));
    }

    private Workspace workspace(String name) {
        return workspaceRepository.save(new Workspace(WorkspaceKind.TEAM, name, null));
    }

    private User user(String email, String name, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", name);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(role);
        User saved = userRepository.save(user);
        if (orgId != null) {
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        }
        return saved;
    }

    private String token(User user) {
        return jwtService.createAccessToken(user);
    }

    private record Key(long id, UUID publicId) {
    }

    private record Account(long id, UUID publicId, String name) {
    }
}
