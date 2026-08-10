package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code PATCH /admin/vms/{vmId}/gateway-block}: flag persistence + the
 * VM-event/audit pair on real transitions, idempotent re-application without
 * new records, SYS_ADMIN-only gate, and the {@code sshGatewayBlocked} field on
 * the admin list. Gateway/terminal enforcement of the flag is covered by the
 * sshgw route and terminal test surfaces.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmGatewayBlockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;
    private String sysAdminToken;
    private String orgAdminToken;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        String slug = "agb-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    @Test
    void blockAndUnblockPersistTheFlagAndRecordEventAndAuditOncePerTransition() throws Exception {
        long vmId = createVm("RUNNING");

        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": true, \"reason\": \"비정상 트래픽 신고 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pub("vms", vmId).toString()))
                .andExpect(jsonPath("$.sshGatewayBlocked").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "select ssh_gateway_blocked from vms where id = ?", Boolean.class, vmId)).isTrue();
        assertThat(eventCount(vmId, "GATEWAY_BLOCK")).isEqualTo(1);
        assertThat(auditCount(vmId, "vm.gateway_block")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select detail from vm_events where vm_id = ? and type = 'GATEWAY_BLOCK'
                """, String.class, vmId)).contains("비정상 트래픽 신고 확인");

        // idempotent re-application: 200, no new event/audit rows
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sshGatewayBlocked").value(true));
        assertThat(eventCount(vmId, "GATEWAY_BLOCK")).isEqualTo(1);
        assertThat(auditCount(vmId, "vm.gateway_block")).isEqualTo(1);

        // the admin list exposes the flag
        mockMvc.perform(get("/api/v1/admin/vms?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')].sshGatewayBlocked".formatted(pub("vms", vmId)))
                        .value(true));

        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sshGatewayBlocked").value(false));
        assertThat(jdbcTemplate.queryForObject(
                "select ssh_gateway_blocked from vms where id = ?", Boolean.class, vmId)).isFalse();
        assertThat(eventCount(vmId, "GATEWAY_UNBLOCK")).isEqualTo(1);
        assertThat(auditCount(vmId, "vm.gateway_unblock")).isEqualTo(1);
    }

    @Test
    void onlySysAdminMayToggleAndUnknownVmAnswers404() throws Exception {
        long vmId = createVm("RUNNING");
        String body = "{\"blocked\": true}";

        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        User sysManager = ensureUser("agb.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER, null);
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(sysManager))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        User plainUser = ensureUser("agb.user@pusan.ac.kr", UserRole.USER, null);
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(plainUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/vms/" + SeedFixtures.UNKNOWN_ID + "/gateway-block")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "select ssh_gateway_blocked from vms where id = ?", Boolean.class, vmId)).isFalse();
    }

    @Test
    void missingBlockedFieldAnswers422() throws Exception {
        long vmId = createVm("RUNNING");
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/gateway-block", pub("vms", vmId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"사유만 있는 요청\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private long eventCount(long vmId, String type) {
        return jdbcTemplate.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = ?::vm_event_type",
                Long.class, vmId, type);
    }

    private long auditCount(long vmId, String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, pub("vms", vmId).toString());
    }

    private User ensureUser(String email, UserRole role, Long userOrgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "차단테스트");
            user.setRole(role);
            user.setOrgId(userOrgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private long createVm(String status) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "차단 테스트", imageId);
        String hostname = "agb-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, status);
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
