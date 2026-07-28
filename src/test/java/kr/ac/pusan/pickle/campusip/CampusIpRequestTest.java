package kr.ac.pusan.pickle.campusip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 교내 IP 신청 workflow: the USER-role gate + group OWNER/EDITOR scoping,
 * port/purpose validation with normalization, the one-live-request rule,
 * REQUESTED-only cancellation, and the admin transition matrix
 * (REQUESTED → APPROVED|REJECTED, APPROVED → GRANTED|REJECTED,
 * GRANTED → REVOKED; GRANTED demands a valid IPv4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class CampusIpRequestTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(905_000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private String ownerToken;
    private String viewerToken;
    private String outsiderToken;
    private String plainUserToken;
    private String sysAdminToken;
    private long groupId;

    @BeforeEach
    void setUp() throws Exception {
        // Manager-tier accounts must belong to an org (users check constraint).
        Long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        owner = ensureUser("cip.owner@pusan.ac.kr", "교내IP소유자", UserRole.ORG_MANAGER, orgId);
        User viewer = ensureUser("cip.viewer@pusan.ac.kr", "교내IP뷰어", UserRole.ORG_MANAGER,
                orgId);
        User outsider = ensureUser("cip.outsider@pusan.ac.kr", "교내IP외부인",
                UserRole.ORG_MANAGER, orgId);
        User plainUser = ensureUser("cip.user@pusan.ac.kr", "교내IP일반", UserRole.USER, null);
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        ownerToken = jwtService.createAccessToken(owner);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        plainUserToken = jwtService.createAccessToken(plainUser);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        groupId = createTeam("cip-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, viewer.getEmail(), "VIEWER");
        addMember(groupId, plainUser.getEmail(), "EDITOR");
    }

    // ── eligibility ─────────────────────────────────────────────────────────

    @Test
    void userRoleIsDeniedAtTheGateEvenAsGroupEditor() throws Exception {
        long vmId = vm();
        // plainUser IS an EDITOR of the group — the role gate still refuses.
        request(vmId, plainUserToken, "서비스 운영", List.of(80))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void groupScopingMasksAndRefusesLikePublishing() throws Exception {
        long vmId = vm();
        request(vmId, outsiderToken, "서비스 운영", List.of(80))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        request(vmId, viewerToken, "서비스 운영", List.of(80))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        // reads only need membership: a VIEWER may list
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/vms/" + vmId + "/campus-ip-requests")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    // ── creation ────────────────────────────────────────────────────────────

    @Test
    void createNormalizesPortsAndNotifiesSysadmins() throws Exception {
        long vmId = vm();
        request(vmId, ownerToken, "웹 서비스 공개", List.of(443, 80, 80, 443, 8080))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.ports[0]").value(80))
                .andExpect(jsonPath("$.ports[1]").value(443))
                .andExpect(jsonPath("$.ports[2]").value(8080))
                .andExpect(jsonPath("$.ports.length()").value(3));
        Long notified = jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and event = 'campus_ip.requested'
                """, Long.class, SeedFixtures.sysadminId(jdbcTemplate));
        assertThat(notified).isPositive();
    }

    @Test
    void createValidatesPortsAndPurpose() throws Exception {
        long vmId = vm();
        request(vmId, ownerToken, "포트 오류", List.of(0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        request(vmId, ownerToken, "포트 오류", List.of(65536))
                .andExpect(status().isUnprocessableEntity());
        request(vmId, ownerToken, " ", List.of(80))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void onlyOneLiveRequestPerVm() throws Exception {
        long vmId = vm();
        request(vmId, ownerToken, "1차 신청", List.of(80)).andExpect(status().isCreated());
        request(vmId, ownerToken, "2차 신청", List.of(443))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPUS_IP_REQUEST_EXISTS"));
    }

    // ── cancellation ────────────────────────────────────────────────────────

    @Test
    void cancelWorksOnlyBeforeReview() throws Exception {
        long vmId = vm();
        long requestId = created(vmId, "취소 테스트", List.of(80));
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/campus-ip-requests/" + requestId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from campus_ip_requests where id = ?", Long.class, requestId))
                .isZero();

        long second = created(vmId, "취소 불가 테스트", List.of(80));
        transition(second, "APPROVED", null).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/campus-ip-requests/" + second)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPUS_IP_INVALID_TRANSITION"));
    }

    // ── admin transitions ───────────────────────────────────────────────────

    @Test
    void adminTransitionsFollowTheMatrix() throws Exception {
        long vmId = vm();
        long requestId = created(vmId, "전환 테스트", List.of(80, 443));

        // REQUESTED -> GRANTED is not legal (must pass APPROVED first)
        transition(requestId, "GRANTED", "203.0.113.10")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPUS_IP_INVALID_TRANSITION"));

        transition(requestId, "APPROVED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // GRANTED demands a valid IPv4
        transition(requestId, "GRANTED", null).andExpect(status().isUnprocessableEntity());
        transition(requestId, "GRANTED", "not-an-ip").andExpect(status().isUnprocessableEntity());
        transition(requestId, "GRANTED", "203.0.113.10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.grantedAddress").value("203.0.113.10"));

        // requester is notified about every processed transition (scoped to
        // THIS request — other tests' transitions also notify the owner)
        Long notified = jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and event = 'campus_ip.status_changed'
                   and (payload ->> 'requestId')::bigint = ?
                """, Long.class, owner.getId(), requestId);
        assertThat(notified).isEqualTo(2);

        transition(requestId, "REVOKED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // REVOKED is terminal
        transition(requestId, "APPROVED", null).andExpect(status().isConflict());

        // the VM is free for a fresh request once no live request remains
        request(vmId, ownerToken, "재신청", List.of(22)).andExpect(status().isCreated());
    }

    @Test
    void rejectionIsTerminal() throws Exception {
        long vmId = vm();
        long requestId = created(vmId, "반려 테스트", List.of(80));
        transition(requestId, "REJECTED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        transition(requestId, "APPROVED", null).andExpect(status().isConflict());
        transition(requestId, "GRANTED", "203.0.113.11").andExpect(status().isConflict());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResultActions request(long vmId, String token, String purpose, List<Integer> ports)
            throws Exception {
        return mockMvc.perform(post("/api/v1/vms/" + vmId + "/campus-ip-requests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("purpose", purpose, "ports", ports))));
    }

    private long created(long vmId, String purpose, List<Integer> ports) throws Exception {
        String body = request(vmId, ownerToken, purpose, ports)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions transition(long requestId, String status, String grantedAddress)
            throws Exception {
        Map<String, Object> body = grantedAddress == null
                ? Map.of("status", status)
                : Map.of("status", status, "grantedAddress", grantedAddress);
        return mockMvc.perform(post("/api/v1/admin/campus-ip-requests/" + requestId + "/status")
                .header("Authorization", "Bearer " + sysAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long vm() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '교내 IP 테스트', (select min(id) from vm_templates),
                        1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId());
        String hostname = "cip-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from vm_templates), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, groupId, orgId, requestId, hostname, hostname,
                VMID_SEQ.incrementAndGet());
        String ip = "172.29.230." + IP_SEQ.getAndIncrement();
        jdbcTemplate.update("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name = 'guest-private'), ?::inet, ?,
                        'ALLOCATED')
                """, ip, vmId);
        return vmId;
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "교내 IP 테스트 " + slug,
                                        "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER,
                                ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService,
                                        ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(role);
            if (orgId != null) {
                user.setOrgId(orgId);
            }
            return userRepository.save(user);
        });
    }
}
