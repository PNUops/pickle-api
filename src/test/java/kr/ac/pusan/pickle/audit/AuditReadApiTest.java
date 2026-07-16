package kr.ac.pusan.pickle.audit;

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
 * canonical <b>derived membership</b> rule enforced in SQL — students belong
 * to an org through groups holding vm_requests / non-DELETED VMs in it.
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
        org = orgRepository.findBySlug("sw-edu").orElseThrow();
        otherOrg = orgRepository.findBySlug("aud-other").orElseGet(() ->
                orgRepository.save(new Org("감사 타기관", "aud-other", null)));
        self = ensureStudent("aud.self@pusan.ac.kr", "감사본인");
        peer = ensureStudent("aud.peer@pusan.ac.kr", "감사동료");
        otherOrgUser = ensureStudent("aud.other@pusan.ac.kr", "감사타인");
        // derived org membership: self+peer share a group with a sw-edu
        // vm_request; the third user's group is linked to the other org only
        long ownGroup = createGroup("audown", self.getId(), peer.getId());
        linkGroupToOrg(ownGroup, org.getId(), self.getId());
        long otherGroup = createGroup("audoth", otherOrgUser.getId());
        linkGroupToOrg(otherGroup, otherOrg.getId(), otherOrgUser.getId());
        selfToken = jwtService.createAccessToken(self);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("orgadmin@pickle.local").orElseThrow());
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("admin@pickle.local").orElseThrow());
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
        // ORG_ADMIN: derived own-org actors only (students via their group's
        // sw-edu request) — the other org's actor is invisible
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
        mockMvc.perform(get("/api/v1/admin/audit?orgId=" + otherOrg.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/audit" + actionFilter + "&orgId=" + otherOrg.getId())
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
        // students → 403
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

    private long createGroup(String prefix, long... memberIds) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id
                """, Long.class, slug, slug);
        for (long memberId : memberIds) {
            jdbcTemplate.update("""
                    insert into group_members (group_id, user_id, role) values (?, ?, 'MEMBER')
                    """, groupId, memberId);
        }
        return groupId;
    }

    /** Derived-membership link: one vm_request of the group in the org. */
    private void linkGroupToOrg(long groupId, long orgId, long requesterId) {
        jdbcTemplate.update("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '조직 연계(테스트)', (select min(id) from vm_templates),
                        1, 1024, 20, false, false, false)
                """, groupId, orgId, requesterId);
    }

    private User ensureStudent(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.USER);
        user.setOrgId(null);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
