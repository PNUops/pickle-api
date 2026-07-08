package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * {@code GET /admin/vms} per contract: ORG_ADMIN hard-scoped to their own org
 * (another org in the orgId filter answers 404 so cross-org existence stays
 * private), SYS_ADMIN across orgs, the orgId/groupId/status filters, paging,
 * and the VmSummary shape. Assertions are per-id (the database is shared
 * with the other admin tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmsTest {

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

    private Org org;
    private Org otherOrg;
    private String studentToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String sysAdminToken;
    private long groupA1;
    private long groupA2;
    private long groupB;
    private long vmRunningA1;
    private long vmStoppedA2;
    private long vmRunningB;

    @BeforeEach
    void setUp() {
        org = orgRepository.findBySlug("sw-edu").orElseThrow();
        otherOrg = orgRepository.findBySlug("advm-other").orElseGet(() ->
                orgRepository.save(new Org("다른 기관", "advm-other", null)));
        User student = ensureUser("advm.student@pusan.ac.kr", "목록학생", UserRole.STUDENT, null);
        User otherOrgAdmin = ensureUser("advm.other.admin@pusan.ac.kr", "타기관관리자",
                UserRole.ORG_ADMIN, otherOrg.getId());
        studentToken = jwtService.createAccessToken(student);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("orgadmin@pickle.local").orElseThrow());
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("admin@pickle.local").orElseThrow());
        groupA1 = createGroup();
        groupA2 = createGroup();
        groupB = createGroup();
        vmRunningA1 = createVm(org.getId(), groupA1, "RUNNING");
        vmStoppedA2 = createVm(org.getId(), groupA2, "STOPPED");
        vmRunningB = createVm(otherOrg.getId(), groupB, "RUNNING");
    }

    @Test
    void orgAdminIsPinnedToTheirOrgAndCrossOrgFilterAnswers404() throws Exception {
        // students have no admin VM list → 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // own org only, with or without the explicit own-org filter
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningB)).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + org.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists());

        // another org in the filter → 404, never 403 (existence stays private)
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + otherOrg.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // and the other org's admin sees the mirror image
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningB)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());
    }

    @Test
    void sysAdminSeesAllOrgsAndMayFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningB)).exists());
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + otherOrg.getId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningB)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/vms?groupId=" + groupA2)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/admin/vms?orgId=%d&status=STOPPED".formatted(org.getId()))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());
    }

    @Test
    void returnsTheVmSummaryShapeAndPages() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vms?groupId=" + groupA1)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(vmRunningA1))
                .andExpect(jsonPath("$.content[0].name").isNotEmpty())
                .andExpect(jsonPath("$.content[0].hostname").isNotEmpty())
                .andExpect(jsonPath("$.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].vcpu").value(2))
                .andExpect(jsonPath("$.content[0].memoryMb").value(2048))
                .andExpect(jsonPath("$.content[0].diskGb").value(10))
                .andExpect(jsonPath("$.content[0].groupId").value(groupA1))
                .andExpect(jsonPath("$.content[0].requestId").isNumber())
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        // paging: size=1 over the two org-A groups → 2 pages, newest first
        mockMvc.perform(get("/api/v1/admin/vms?orgId=%d&size=1".formatted(org.getId()))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1));

        // page/size validation is the shared 422 handler's business
        mockMvc.perform(get("/api/v1/admin/vms?size=0")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent());
    }

    private static String byId(long vmId) {
        return "$.content[?(@.id == %d)]".formatted(vmId);
    }

    private long createGroup() {
        String slug = "advm-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    /** Minimal request→vm FK chain (2 vCPU / 2048 MiB / 10 GiB). */
    private long createVm(long orgId, long groupId, String status) {
        long templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        long requesterId = jdbcTemplate.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '관리자 목록 테스트', ?, 2, 2048, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "advm-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, status);
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
