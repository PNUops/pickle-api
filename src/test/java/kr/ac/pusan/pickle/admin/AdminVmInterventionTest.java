package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Admin VM intervention surface (contract v0.17.0): org-scoped detail/events
 * reads and power intents. Ratified decisions under test: all four admin roles
 * may intervene (org tier on own-org VMs only, 404 mask), admin power ops
 * bypass the workspace-internal stop protection, the expiry guard on start stays,
 * and every accepted power intent leaves an audit row. The JobRunr server is
 * off so accepted intents stay observable as pending claims.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmInterventionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;
    private String sysAdminToken;
    private String orgAdminToken;
    private String orgManagerToken;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        orgManagerToken = jwtService.createAccessToken(
                ensureUser("avi.orgmanager@pusan.ac.kr", UserRole.ORG_MANAGER, orgId));
        String slug = "avi-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    @Test
    void adminDetailAndEventsAreOrgScopedWithThe404Mask() throws Exception {
        long vmId = createVm("RUNNING", null);
        jdbcTemplate.update("""
                insert into vm_events (vm_id, type, actor_kind, detail)
                values (?, 'CREATE', 'SYSTEM', '생성')
                """, vmId);

        // all admin tiers read the detail; the viewer has no workspace role
        mockMvc.perform(get("/api/v1/admin/vms/{id}", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pub("vms", vmId).toString()))
                .andExpect(jsonPath("$.myResourceRole").isEmpty())
                .andExpect(jsonPath("$.passwordRevealAllowed").value(false));
        mockMvc.perform(get("/api/v1/admin/vms/{id}", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pub("vms", vmId).toString()));

        mockMvc.perform(get("/api/v1/admin/vms/{id}/events", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("CREATE"))
                .andExpect(jsonPath("$.content[0].actorKind").value("SYSTEM"));

        // the intervention the member surface reports without a name: an
        // administrator reading the same history sees who it was
        User intervener = ensureUser("avi.intervener@pusan.ac.kr", UserRole.ORG_ADMIN, orgId);
        jdbcTemplate.update("""
                insert into vm_events (vm_id, type, actor_id, actor_kind, detail)
                values (?, 'GATEWAY_BLOCK', ?, 'ADMIN', '관리자 차단')
                """, vmId, intervener.getId());
        mockMvc.perform(get("/api/v1/admin/vms/{id}/events", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorKind").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].actorId").value(
                        intervener.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].actorName").value(intervener.getName()));

        // …but not to the one role the audit log leaves out. ORG_VIEWER is what
        // an organisation grants another organisation's staff, and it reads this
        // page; naming the administrator here would hand that reader something
        // /admin/audit refuses them, as a side effect of a display change.
        String orgViewerToken = jwtService.createAccessToken(
                ensureUser("avi.orgviewer@pusan.ac.kr", UserRole.ORG_VIEWER, orgId));
        mockMvc.perform(get("/api/v1/admin/vms/{id}/events", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorKind").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].actorId").value((Object) null))
                .andExpect(jsonPath("$.content[0].actorName").value((Object) null));

        // cross-org admin: same 404 as an unknown id
        Org otherOrg = orgRepository.findFirstByNameOrderByIdAsc("개입 테스트 타기관").orElseGet(() ->
                orgRepository.save(new Org("개입 테스트 타기관", null)));
        String otherOrgAdminToken = jwtService.createAccessToken(
                ensureUser("avi.other.admin@pusan.ac.kr", UserRole.ORG_ADMIN, otherOrg.getId()));
        mockMvc.perform(get("/api/v1/admin/vms/{id}", pub("vms", vmId))
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/vms/{id}/events", pub("vms", vmId))
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound());

        // a plain user is refused by the role gate
        String userToken = jwtService.createAccessToken(
                ensureUser("avi.user@pusan.ac.kr", UserRole.USER, null));
        mockMvc.perform(get("/api/v1/admin/vms/{id}", pub("vms", vmId))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPowerIntentsAreAcceptedAuditedAndSerializedByTheClaim() throws Exception {
        long vmId = createVm("STOPPED", null);

        // ORG_MANAGER may start an own-org VM (ratified scope)
        mockMvc.perform(post("/api/v1/admin/vms/{id}/start", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgManagerToken))
                .andExpect(status().isAccepted());
        assertThat(jdbcTemplate.queryForObject(
                "select pending_power_action from vms where id = ?", String.class, vmId))
                .isEqualTo("START");
        assertThat(auditCount(vmId, "vm.admin_start")).isEqualTo(1);

        // duplicate while the claim is in flight → 409, no extra audit row
        mockMvc.perform(post("/api/v1/admin/vms/{id}/start", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        assertThat(auditCount(vmId, "vm.admin_start")).isEqualTo(1);

        // wrong-state intent → 409 (RUNNING-only op on a STOPPED VM)
        long stopped = createVm("STOPPED", null);
        mockMvc.perform(post("/api/v1/admin/vms/{id}/shutdown", pub("vms", stopped))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict());

        // reboot + force-stop accept from RUNNING and audit
        long running = createVm("RUNNING", null);
        mockMvc.perform(post("/api/v1/admin/vms/{id}/reboot", pub("vms", running))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isAccepted());
        assertThat(auditCount(running, "vm.admin_reboot")).isEqualTo(1);
        mockMvc.perform(post("/api/v1/admin/vms/{id}/force-stop", pub("vms", running))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted());
        assertThat(auditCount(running, "vm.admin_force_stop")).isEqualTo(1);
    }

    @Test
    void adminShutdownBypassesStopProtection() throws Exception {
        long vmId = createVm("RUNNING", null);
        jdbcTemplate.update("""
                insert into vm_settings (vm_id, key, value) values (?, 'stop_protection', 'true'::jsonb)
                """, vmId);

        mockMvc.perform(post("/api/v1/admin/vms/{id}/shutdown", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isAccepted());
        assertThat(auditCount(vmId, "vm.admin_shutdown")).isEqualTo(1);
    }

    @Test
    void expiredVmStillRefusesAdminStart() throws Exception {
        LocalDate today = LocalDate.now(KST);
        long vmId = createVm("STOPPED", today.minusDays(2));

        mockMvc.perform(post("/api/v1/admin/vms/{id}/start", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_EXPIRED"));
        assertThat(auditCount(vmId, "vm.admin_start")).isZero();
    }

    @Test
    void crossOrgPowerIntentIsMaskedAs404() throws Exception {
        Org otherOrg = orgRepository.findFirstByNameOrderByIdAsc("개입 전원 타기관").orElseGet(() ->
                orgRepository.save(new Org("개입 전원 타기관", null)));
        String otherOrgAdminToken = jwtService.createAccessToken(
                ensureUser("avi.pother.admin@pusan.ac.kr", UserRole.ORG_ADMIN, otherOrg.getId()));
        long vmId = createVm("STOPPED", null);

        mockMvc.perform(post("/api/v1/admin/vms/{id}/start", pub("vms", vmId))
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "select pending_power_action from vms where id = ?", String.class, vmId)).isNull();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private long auditCount(long vmId, String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, pub("vms", vmId).toString());
    }

    private User ensureUser(String email, UserRole role, Long userOrgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "개입테스트");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), userOrgId, role);
            return saved;
        });
    }

    private long createVm(String status, LocalDate endDate) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "개입 테스트", imageId);
        String hostname = "avi-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, status, endDate);
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
