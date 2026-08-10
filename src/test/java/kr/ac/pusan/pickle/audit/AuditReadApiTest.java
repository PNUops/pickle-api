package kr.ac.pusan.pickle.audit;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Audit read APIs per contract v0.5.0: {@code /me/activity} is strictly
 * self-scoped (login rows included, filters cannot widen it), and
 * {@code /admin/audit} pins ORG_ADMIN to actors of their own org via the
 * canonical <b>derived membership</b> rule enforced in SQL — regular users belong
 * to an org through workspaces holding requests / non-DELETED VMs in it.
 * System rows (null actor) and cross-org actors stay invisible; a cross-org
 * {@code orgId} masks as 404; SYS_ADMIN sees everything with filters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AuditReadApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org org;
    private Org otherOrg;
    private User self;
    private User peer;
    private User otherOrgUser;
    private String selfToken;
    private String orgAdminToken;
    private String sysAdminToken;
    /** Unique per test run — isolates assertions from shared-DB audit noise. */
    private String runTag;

    @BeforeEach
    void setUp() {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("감사 타기관").orElseGet(() ->
                orgRepository.save(new Org("감사 타기관", null)));
        self = ensureRegularUser("aud.self@pusan.ac.kr", "감사본인");
        peer = ensureRegularUser("aud.peer@pusan.ac.kr", "감사동료");
        otherOrgUser = ensureRegularUser("aud.other@pusan.ac.kr", "감사타인");
        // derived org membership: self+peer share a workspace with a seed-org
        // request; the third user's workspace is linked to the other org only
        long ownWorkspace = createWorkspace("audown", self.getId(), peer.getId());
        linkWorkspaceToOrg(ownWorkspace, org.getId(), self.getId());
        long otherWorkspace = createWorkspace("audoth", otherOrgUser.getId());
        linkWorkspaceToOrg(otherWorkspace, otherOrg.getId(), otherOrgUser.getId());
        selfToken = jwtService.createAccessToken(self);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        runTag = UUID.randomUUID().toString().substring(0, 8);

        insertAudit(self.getId(), "USER", "auth.login", null, null, "10.0.0.1");
        insertAudit(self.getId(), "USER", "auth.login_failed", null, null, "10.0.0.1");
        insertAudit(self.getId(), "USER", "test." + runTag + ".vmdel", "vm", 5511L, "10.0.0.1");
        insertAudit(peer.getId(), "USER", "test." + runTag + ".vmdel", "vm", 5522L, "10.0.0.2");
        insertAudit(otherOrgUser.getId(), "USER", "test." + runTag + ".vmdel", "vm", 5533L,
                "10.0.0.3");
        // system row: no actor at all — SYS_ADMIN-only in the admin view
        insertAudit(null, null, "test." + runTag + ".system", "vm", 5544L, null);
    }

    @Test
    void myActivityIsStrictlySelfScoped() throws Exception {
        // own rows only — the peer's identical action never shows
        mockMvc.perform(get("/api/v1/me/activity?action=test." + runTag + ".vmdel")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetType").value("vm"))
                .andExpect(jsonPath("$.content[0].targetId").value("5511"))
                .andExpect(jsonPath("$.content[0].detail.vmName").value("aud-vm"))
                .andExpect(jsonPath("$.content[0].ip").value("10.0.0.1"));
        // login history (success and failure) is part of the self view
        mockMvc.perform(get("/api/v1/me/activity?action=auth.login")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.action=='auth.login')]").exists());
        mockMvc.perform(get("/api/v1/me/activity?action=auth.login_failed")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.action=='auth.login_failed')]").exists());
        // date window: the future excludes everything, the past ends before today
        mockMvc.perform(get("/api/v1/me/activity?from=2099-01-01")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/me/activity?to=2000-01-01")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // 401 unauthenticated
        mockMvc.perform(get("/api/v1/me/activity")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminAuditScopesOrgAdminByDerivedMembershipInSql() throws Exception {
        String actionFilter = "?action=test." + runTag + ".vmdel&size=100";
        // ORG_ADMIN: derived own-org actors only (users via their workspace's
        // seed-org request) — the other org's actor is invisible
        mockMvc.perform(get("/api/v1/admin/audit" + actionFilter)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.self@pusan.ac.kr')]").exists())
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.peer@pusan.ac.kr')]").exists())
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.other@pusan.ac.kr')]")
                        .doesNotExist());
        // system rows (null actor) belong to no org — ORG_ADMIN never sees them
        mockMvc.perform(get("/api/v1/admin/audit?action=test." + runTag + ".system")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/admin/audit?action=test." + runTag + ".system")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorId").value((Object) null))
                .andExpect(jsonPath("$.content[0].actorEmail").value((Object) null));
        // cross-org orgId → 404 mask; SYS_ADMIN may filter by org
        mockMvc.perform(get("/api/v1/admin/audit?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/audit" + actionFilter + "&orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorEmail").value("aud.other@pusan.ac.kr"));
        // filters: actorEmail + targetType + targetId
        mockMvc.perform(get("/api/v1/admin/audit?actorEmail=aud.self@pusan.ac.kr&action=test."
                        + runTag + ".vmdel")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorName").value("감사본인"))
                .andExpect(jsonPath("$.content[0].actorRole").value("USER"));
        mockMvc.perform(get("/api/v1/admin/audit?targetType=vm&targetId=5533&action=test."
                        + runTag + ".vmdel")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetId").value("5533"));
        // users → 403
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isForbidden());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void insertAudit(Long actorId, String actorRole, String action, String targetType,
            Long targetId, String ip) {
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, target_type, target_id, detail, ip)
                values (?, ?, ?, ?, ?, '{"vmName":"aud-vm"}'::jsonb, ?)
                """, actorId, actorRole, action, targetType, targetId, ip);
    }

    private long createWorkspace(String prefix, long... memberIds) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM', ?) returning id
                """, Long.class, slug);
        for (long memberId : memberIds) {
            jdbcTemplate.update("""
                    insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'MEMBER')
                    """, workspaceId, memberId);
        }
        return workspaceId;
    }

    /** Derived-membership link: one request of the workspace in the org. */
    private void linkWorkspaceToOrg(long workspaceId, long orgId, long requesterId) {
        RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "조직 연계(테스트)", null, 1, 1024, 20);
    }

    private User ensureRegularUser(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.USER);
        user.setOrgId(null);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
