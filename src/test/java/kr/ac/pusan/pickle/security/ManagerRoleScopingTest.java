package kr.ac.pusan.pickle.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Service-layer scoping for the two operator tiers, complementing the
 * annotation-level {@link PermissionMatrixTest}:
 *
 * <ul>
 *   <li>ORG_MANAGER is pinned to its own org exactly like ORG_ADMIN — foreign-org
 *       targets are masked as 404 across the admin read surfaces, and it may
 *       reject/approve its own org's requests;</li>
 *   <li>SYS_MANAGER reaches every SYS_ADMIN read surface (200) but is refused
 *       (403) every dangerous op on the §4 backstop list.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ManagerRoleScopingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org orgB;
    private String orgManagerAToken;
    private String sysManagerToken;
    private User foreignAdminB;
    private long requestInOrgA;
    private long requestInOrgB;

    @BeforeEach
    void setUp() {
        Org orgA = orgRepository.findBySlug("sw-edu").orElseThrow();
        orgB = orgRepository.findBySlug("mgr-org-b")
                .orElseGet(() -> orgRepository.save(new Org("운영자 테스트 기관 B", "mgr-org-b", null)));

        User orgManagerA = ensureUser("mgr.orgmgr.a@pusan.ac.kr", "기관운영자A",
                UserRole.ORG_MANAGER, orgA.getId());
        User sysManager = ensureUser("mgr.sysmgr@pusan.ac.kr", "시스템운영자",
                UserRole.SYS_MANAGER, null);
        foreignAdminB = ensureUser("mgr.orgadmin.b@pusan.ac.kr", "기관B관리자",
                UserRole.ORG_ADMIN, orgB.getId());
        User applicant = ensureUser("mgr.applicant@pusan.ac.kr", "신청자", UserRole.USER, null);

        orgManagerAToken = jwtService.createAccessToken(orgManagerA);
        sysManagerToken = jwtService.createAccessToken(sysManager);

        requestInOrgA = insertSubmittedRequest(orgA.getId(), applicant.getId());
        requestInOrgB = insertSubmittedRequest(orgB.getId(), applicant.getId());
    }

    // ── ORG_MANAGER: org-scoped like ORG_ADMIN ────────────────────────────────

    @Test
    void orgManagerReadsOwnOrgAndMasksForeignOrgAs404() throws Exception {
        // own-org request is visible; the foreign-org request is masked
        get("/api/v1/admin/vm-requests/" + requestInOrgA, orgManagerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) requestInOrgA));
        get("/api/v1/admin/vm-requests/" + requestInOrgB, orgManagerAToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        get("/api/v1/admin/vm-requests/" + requestInOrgB + "/context", orgManagerAToken)
                .andExpect(status().isNotFound());

        // deciding a foreign-org request is masked as 404, not 403 (existence privacy)
        postJson("/api/v1/admin/vm-requests/" + requestInOrgB + "/reject", orgManagerAToken,
                Map.of("comment", "타 기관 반려 시도"))
                .andExpect(status().isNotFound());

        // the admin VMs / users / audit surfaces reject a foreign orgId with 404
        get("/api/v1/admin/vms?orgId=" + orgB.getId(), orgManagerAToken)
                .andExpect(status().isNotFound());
        get("/api/v1/admin/users/" + foreignAdminB.getId(), orgManagerAToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        get("/api/v1/admin/audit?orgId=" + orgB.getId(), orgManagerAToken)
                .andExpect(status().isNotFound());

        // and it can read its own org's dashboard + queue
        get("/api/v1/admin/summary", orgManagerAToken).andExpect(status().isOk());
        get("/api/v1/admin/vm-requests", orgManagerAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestInOrgA)).exists())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestInOrgB)).doesNotExist());
    }

    @Test
    void orgManagerMayDecideOwnOrgRequest() throws Exception {
        // §3.9 †15: approve/reject is the org tier's daily load, granted org-scoped
        postJson("/api/v1/admin/vm-requests/" + requestInOrgA + "/reject", orgManagerAToken,
                Map.of("comment", "기관 자원 여유 부족으로 반려합니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.review.decision").value("REJECT"));
    }

    // ── SYS_MANAGER: full read, no dangerous ops ──────────────────────────────

    @Test
    void sysManagerReachesEverySysReadSurface() throws Exception {
        for (String path : new String[] {"/admin/system-summary", "/admin/settings", "/admin/nodes",
                "/admin/ip-allocations", "/admin/tasks", "/admin/drift-findings", "/admin/notifications",
                "/admin/users", "/admin/vms", "/admin/routes"}) {
            get("/api/v1" + path, sysManagerToken).andExpect(status().isOk());
        }
        // system-wide queue: sees both orgs' requests (no org pin)
        get("/api/v1/admin/vm-requests", sysManagerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestInOrgA)).exists())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestInOrgB)).exists());
    }

    @Test
    void sysManagerIsForbiddenEveryDangerousOp() throws Exception {
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        // org / role / account / credential mutations
        postJson("/api/v1/admin/orgs", sysManagerToken,
                Map.of("name", "차단 기관", "slug", "sm-deny-" + slug())).andExpect(status().isForbidden());
        patchJson("/api/v1/admin/orgs/1", sysManagerToken, Map.of("name", "x"))
                .andExpect(status().isForbidden());
        patchJson("/api/v1/admin/users/1", sysManagerToken, Map.of("role", "USER"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/admin/users/1/disable", sysManagerToken, Map.of("reason", "차단"))
                .andExpect(status().isForbidden());
        post("/api/v1/admin/users/1/enable", sysManagerToken).andExpect(status().isForbidden());
        post("/api/v1/admin/users/1/mfa-reset", sysManagerToken).andExpect(status().isForbidden());
        // settings kill switch + VM force-delete / deletion lifecycle
        putJson("/api/v1/admin/settings/ssh_gateway_enabled", sysManagerToken, Map.of("value", false))
                .andExpect(status().isForbidden());
        postJson("/api/v1/admin/vms/1/force-delete", sysManagerToken, Map.of("confirmName", "x"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/admin/vms/1/schedule-delete", sysManagerToken,
                Map.of("scheduledFor", future, "reason", "x")).andExpect(status().isForbidden());
        post("/api/v1/admin/vms/1/cancel-scheduled-delete", sysManagerToken)
                .andExpect(status().isForbidden());
        // broadcast + approval decisions
        postJson("/api/v1/admin/announcements", sysManagerToken,
                Map.of("title", "x", "body", "y", "scope", "ALL")).andExpect(status().isForbidden());
        postJson("/api/v1/admin/vm-requests/" + requestInOrgA + "/reject", sysManagerToken,
                Map.of("comment", "차단")).andExpect(status().isForbidden());
    }

    @Test
    void managerTiersGetNoDeleteVmAdminOverride() throws Exception {
        // deleteVm (DELETE /vms/{id}) is group OWNER-scoped for both manager tiers
        // — unlike ORG_ADMIN (own org) / SYS_ADMIN, they get NO admin override, so
        // deleting a VM they do not own is masked as 404 (existence privacy). This
        // closes an audit blind spot: the annotation matrix allows the call, and
        // only the service-layer OWNER check keeps them out.
        long vmInOrgB = insertActiveVmInOrg(orgB.getId());
        delete("/api/v1/vms/" + vmInOrgB, orgManagerAToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        delete("/api/v1/vms/" + vmInOrgB, sysManagerToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResultActions get(String uri, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(uri).header("Authorization", "Bearer " + token));
    }

    private ResultActions post(String uri, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(uri).header("Authorization", "Bearer " + token));
    }

    private ResultActions delete(String uri, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(uri).header("Authorization", "Bearer " + token));
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri),
                token, body);
    }

    private ResultActions patchJson(String uri, String token, Map<String, ?> body) throws Exception {
        return json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(uri),
                token, body);
    }

    private ResultActions putJson(String uri, String token, Map<String, ?> body) throws Exception {
        return json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(uri),
                token, body);
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private long insertSubmittedRequest(long orgId, long requesterId) {
        long templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        long groupId = jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, ?, ?) returning id
                """, Long.class, "mgr-grp-" + slug(), "mgr-grp-" + slug());
        return jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb, need_ssh, need_http, need_public)
                values (?, ?, ?, '운영자 스코핑 테스트', ?, 2, 2048, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
    }

    private long insertActiveVmInOrg(long orgId) {
        long templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        long groupId = jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, ?, ?) returning id
                """, Long.class, "mgr-vmgrp-" + slug(), "mgr-vmgrp-" + slug());
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb, need_ssh, need_http, need_public)
                values (?, ?, ?, 'deleteVm 오버라이드 테스트', ?, 2, 2048, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, foreignAdminB.getId(), templateId);
        String hostname = "mgr-vm-" + slug();
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'RUNNING'::vm_status) returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }

    private static String slug() {
        return UUID.randomUUID().toString().substring(0, 8);
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
