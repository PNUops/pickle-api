package kr.ac.pusan.pickle.audit;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.List;
import java.util.Map;
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
 * Personal history is actor-scoped. Institution history follows the captured,
 * verified target institution in SQL, independently of the actor's current
 * memberships. Unclassified history remains SYS-only and filters cannot widen scope.
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
    @Autowired
    private AuditService auditService;

    private Org org;
    private Org otherOrg;
    private User self;
    private User peer;
    private User otherOrgUser;
    private String selfToken;
    private String orgAdminToken;
    private String sysAdminToken;
    private long firstOwnTarget;
    private long secondOwnTarget;
    private long foreignTarget;
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
        // Current actor memberships deliberately differ from some audit targets.
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
        firstOwnTarget = createVm(ownWorkspace, org.getId(), self.getId());
        secondOwnTarget = createVm(ownWorkspace, org.getId(), self.getId());
        foreignTarget = createVm(otherWorkspace, otherOrg.getId(), otherOrgUser.getId());
        recordTargetAudit(self, firstOwnTarget);
        recordTargetAudit(otherOrgUser, secondOwnTarget);
        recordTargetAudit(peer, foreignTarget);
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

    /**
     * The row's own number never leaves the server. Both audit responses type
     * {@code id} as an opaque rendering key, which is exactly why nothing
     * objected while it carried the sequential {@code audit_logs.id} — and a
     * user reading their own activity twice could read the platform's total
     * audit volume and its growth rate off it.
     */
    @Test
    void auditRowsAreIdentifiedByTheirPublicIdNotTheRowNumber() throws Exception {
        String action = "test." + runTag + ".vmdel";
        Long rowNumber = jdbcTemplate.queryForObject(
                "select id from audit_logs where action = ? and actor_id = ?",
                Long.class, action, self.getId());
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from audit_logs where id = ?", UUID.class, rowNumber);

        // the user's own history
        mockMvc.perform(get("/api/v1/me/activity?action=" + action)
                        .header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(publicId.toString()))
                .andExpect(jsonPath("$.content[0].id")
                        .value(not(String.valueOf(rowNumber))));
        // and the same row through the admin view
        mockMvc.perform(get("/api/v1/admin/audit?action=" + action
                        + "&actorEmail=aud.self@pusan.ac.kr")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(publicId.toString()))
                .andExpect(jsonPath("$.content[0].id")
                        .value(not(String.valueOf(rowNumber))));
    }

    @Test
    void adminAuditScopesInstitutionByCapturedTargetInSql() throws Exception {
        String actionFilter = "?action=test." + runTag + ".targeted&size=100";
        // A foreign actor's own-institution target is visible; an own actor's foreign target is not.
        mockMvc.perform(get("/api/v1/admin/audit" + actionFilter)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.self@pusan.ac.kr')]").exists())
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.other@pusan.ac.kr')]").exists())
                .andExpect(jsonPath("$.content[?(@.actorEmail=='aud.peer@pusan.ac.kr')]").doesNotExist())
                .andExpect(jsonPath("$.content[0].targetOrgId").value(org.getPublicId().toString()))
                .andExpect(jsonPath("$.content[1].targetOrgId").value(org.getPublicId().toString()));
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs where action=? and target_org_id=?",
                Long.class, "test." + runTag + ".targeted", org.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs where action=? and target_org_id=?",
                Long.class, "test." + runTag + ".targeted", otherOrg.getId())).isEqualTo(1);
        // Genuinely unclassified system history stays SYS-only.
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
                .andExpect(jsonPath("$.content[0].actorEmail").value("aud.peer@pusan.ac.kr"))
                .andExpect(jsonPath("$.content[0].targetOrgId").value(otherOrg.getPublicId().toString()));
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

    @Test
    void currentMembershipAndResourceChangesCannotReclassifyAuditHistory() throws Exception {
        jdbcTemplate.update("delete from workspace_members where user_id in (?,?,?)",
                self.getId(), peer.getId(), otherOrgUser.getId());
        long moved = createWorkspace("audmoved", self.getId(), peer.getId());
        linkWorkspaceToOrg(moved, otherOrg.getId(), self.getId());
        long opposite = createWorkspace("audopposite", otherOrgUser.getId());
        linkWorkspaceToOrg(opposite, org.getId(), otherOrgUser.getId());
        jdbcTemplate.update("update vms set org_id=? where id=?", otherOrg.getId(), firstOwnTarget);
        jdbcTemplate.update("update vms set org_id=? where id=?", org.getId(), foreignTarget);

        mockMvc.perform(get("/api/v1/admin/audit?action=test." + runTag + ".targeted")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.targetId=='%s')]".formatted(vmPublicId(firstOwnTarget))).exists())
                .andExpect(jsonPath("$.content[?(@.targetId=='%s')]".formatted(vmPublicId(secondOwnTarget))).exists())
                .andExpect(jsonPath("$.content[?(@.targetId=='%s')]".formatted(vmPublicId(foreignTarget))).doesNotExist());

        String legacyAction = "test." + runTag + ".vmdel";
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs a where a.action=? and "
                + AuditTargetInstitutions.verifiedIdSql() + " is null", Long.class, legacyAction)).isEqualTo(3);
        mockMvc.perform(get("/api/v1/admin/audit?action=" + legacyAction)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        for (UserRole role : List.of(UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            User systemReader = new User("audit-system-" + UUID.randomUUID() + "@example.test", "{noop}unused", "합성 시스템 열람자");
            systemReader.setRole(role);
            systemReader.setStatus(UserStatus.ACTIVE);
            String token = jwtService.createAccessToken(userRepository.saveAndFlush(systemReader));
            mockMvc.perform(get("/api/v1/admin/audit?action=" + legacyAction)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.content[0].targetOrgId").isEmpty());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void insertAudit(Long actorId, String actorRole, String action, String targetType,
            Long targetId, String ip) {
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, target_type, target_id, detail, ip)
                values (?, ?, ?, ?, ?, '{"vmName":"aud-vm"}'::jsonb, ?)
                """, actorId, actorRole, action, targetType, targetId, ip);
    }

    private void recordTargetAudit(User actor, long target) {
        auditService.record(actor.getId(), actor.getRole().name(), "test." + runTag + ".targeted", "vm",
                vmPublicId(target), Map.of("vmName", "aud-verified"), "10.0.0.1");
    }

    private UUID vmPublicId(long target) { return SeedFixtures.publicId(jdbcTemplate, "vms", target); }

    private long createVm(long workspaceId, long institutionId, long requesterId) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, institutionId, requesterId,
                "검증된 감사 대상", imageId, 1, 1024, 20);
        String name = "audit-target-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values((select min(id) from nodes),?,?,?,?,?,?,1,1024,20,'RUNNING') returning id
                """, Long.class, workspaceId, institutionId, requestId, name, name, imageId);
    }

    private long createWorkspace(String prefix, long... memberIds) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT', ?) returning id
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
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
