package kr.ac.pusan.pickle.vm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
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

/**
 * User surface assembly per contract v0.3.1: the VM event history
 * endpoint (visibility + newest-first paging), the VmDetail lifecycle fields
 * (provisioning task view with Korean step labels, pending deletion,
 * passwordAvailable, ipAddress), VmSummary.groupName,
 * GroupDetail.myRole and OrgSummary.status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmUserSurfaceTest {

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(940_000);
    private static final AtomicInteger IP_SEQ = new AtomicInteger(0);

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
    private User viewer;
    private User outsider;
    private String ownerToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;
    private String groupName;

    @BeforeEach
    void setUp() throws Exception {
        owner = ensureUser("vmsurf.owner@pusan.ac.kr", "표면소유자");
        viewer = ensureUser("vmsurf.viewer@pusan.ac.kr", "표면뷰어");
        outsider = ensureUser("vmsurf.outsider@pusan.ac.kr", "표면외부인");
        ownerToken = jwtService.createAccessToken(owner);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        String slug = "vmsurf-" + UUID.randomUUID().toString().substring(0, 8);
        groupName = "표면 테스트 " + slug;
        groupId = createTeam(slug, groupName);
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    @Test
    void vmEventsPageNewestFirstWithVisibilityScoping() throws Exception {
        long vmId = createVm();
        jdbcTemplate.update("""
                insert into vm_events (vm_id, type, actor_id, detail)
                values (?, 'CREATE', null, '승인에 따라 자동 생성'), (?, 'START', ?, null)
                """, vmId, vmId, owner.getId());

        // members (VIEWER+) read, newest first
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/events")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("START"))
                .andExpect(jsonPath("$.content[0].actorId").value(owner.getId()))
                .andExpect(jsonPath("$.content[0].detail").value((Object) null))
                .andExpect(jsonPath("$.content[1].type").value("CREATE"))
                .andExpect(jsonPath("$.content[1].actorId").value((Object) null))
                .andExpect(jsonPath("$.content[1].detail").value("승인에 따라 자동 생성"))
                .andExpect(jsonPath("$.content[1].createdAt").isNotEmpty());

        // paging envelope
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/events?size=1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        // non-member → 404 (existence masked, v0.3.2), unknown VM → 404,
        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/events")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/vms/999999/events")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vmDetailAssemblesLifecycleSurface() throws Exception {
        long vmId = createVm();
        long allocationId = allocateIp(vmId);
        String ip = jdbcTemplate.queryForObject(
                "select host(ip) from ip_allocations where id = ?", String.class, allocationId);
        // in-flight PROVISION task at step 4 (템플릿 복제), one prior attempt error
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts, last_error)
                values (?, 'PROVISION', 4, 'RUNNING', 2, '일시 오류')
                """, vmId);
        // pending self-deletion + unread initial password
        jdbcTemplate.update("""
                update vms
                   set delete_kind = 'SELF', delete_scheduled_for = now() + interval '7 days',
                       delete_requested_at = now(), delete_requested_by = ?,
                       password_enc = 'v1:iv:ct', password_hash = 'h'
                 where id = ?
                """, owner.getId(), vmId);

        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value(groupName))
                .andExpect(jsonPath("$.ipAddress").value(ip))
                .andExpect(jsonPath("$.passwordAvailable").value(true))
                .andExpect(jsonPath("$.provisioning.kind").value("PROVISION"))
                .andExpect(jsonPath("$.provisioning.status").value("RUNNING"))
                .andExpect(jsonPath("$.provisioning.currentStep").value(4))
                .andExpect(jsonPath("$.provisioning.totalSteps").value(11))
                .andExpect(jsonPath("$.provisioning.stepLabel").value("템플릿 복제 중"))
                .andExpect(jsonPath("$.provisioning.attempts").value(2))
                .andExpect(jsonPath("$.provisioning.lastError").value("일시 오류"))
                .andExpect(jsonPath("$.deletion.kind").value("SELF"))
                .andExpect(jsonPath("$.deletion.requestedById").value(owner.getId()))
                .andExpect(jsonPath("$.deletion.cancelable").value(true))
                .andExpect(jsonPath("$.sshUsername").value("ubuntu"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // a cleanly finished task and a consumed password flip the surface
        jdbcTemplate.update("update provisioning_tasks set status = 'DONE' where vm_id = ?", vmId);
        jdbcTemplate.update("""
                update vms set password_enc = null, delete_kind = null,
                               delete_scheduled_for = null, delete_requested_at = null,
                               delete_requested_by = null
                 where id = ?
                """, vmId);
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioning").value((Object) null))
                .andExpect(jsonPath("$.deletion").value((Object) null))
                .andExpect(jsonPath("$.passwordAvailable").value(false));
    }

    @Test
    void vmDetailHidesIpReallocatedToAnotherVm() throws Exception {
        // a stale ip_allocation_id pointer left by a crashed release: the
        // allocation row now belongs to another VM and must not be shown
        long vmId = createVm();
        long otherVmId = createVm();
        long allocationId = allocateIp(otherVmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocationId, vmId);

        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipAddress").value((Object) null));
    }

    @Test
    void vmListCarriesGroupName() throws Exception {
        long vmId = createVm();
        mockMvc.perform(get("/api/v1/vms?groupId=" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(vmId))
                .andExpect(jsonPath("$.content[0].groupName").value(groupName));
    }

    @Test
    void groupDetailExposesMyRoleAndOrgSummaryExposesStatus() throws Exception {
        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("OWNER"));
        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("VIEWER"));

        // USER tokens only see non-hidden ACTIVE orgs, so provide one
        jdbcTemplate.update(
                "insert into orgs (name, slug) values ('표면 공개 기관', 'vus-visible')"
                        + " on conflict (slug) do nothing");
        mockMvc.perform(get("/api/v1/orgs")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'vus-visible')].status")
                        .value(org.hamcrest.Matchers.contains("ACTIVE")));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long allocateIp(long vmId) {
        String ip = "172.29.78." + (IP_SEQ.incrementAndGet() % 250 + 1);
        long poolId = jdbcTemplate.queryForObject(
                "select id from ip_pools where name = 'guest-private'", Long.class);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values (?, ?::inet, ?, 'ALLOCATED')
                on conflict (ip) do update set vm_id = excluded.vm_id, status = 'ALLOCATED',
                                               released_at = null
                returning id
                """, Long.class, poolId, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocationId, vmId);
        return allocationId;
    }

    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '표면 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmsurf-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING')
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, VMID_SEQ.incrementAndGet());
    }

    private long createTeam(String slug, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", name, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Sudo-mode gate: mint the caller's X-Reauth-Token for the protected call. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
