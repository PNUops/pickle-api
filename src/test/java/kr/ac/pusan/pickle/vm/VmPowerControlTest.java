package kr.ac.pusan.pickle.vm;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.provisioning.VmPowerJobs;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * B4 power control per contract v0.3.1: the per-op 409 state matrix,
 * group-role authorization (non-member 404 masking / VIEWER 403), the
 * accept-time REBOOTING intent transition + after-commit enqueue, and the
 * {@link VmPowerJobs} worker against WireMock-served real pve1 captures
 * (start happy path; ACPI-timeout shutdown failure without force fallback).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmPowerControlTest {

    /** Node name used in the WireMock stub paths (unique test inventory row). */
    private static final String NODE_NAME = "wm-power";

    /** Unique proxmox_vmid per created VM (global unique column). */
    private static final java.util.concurrent.atomic.AtomicInteger VMID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(910_000);

    private static final String START_UPID =
            "UPID:pve1:0006D8F7:005491B8:6A4E2CC2:qmstart:102:pickle@pve!pickle-api:";
    private static final String SHUTDOWN_UPID =
            "UPID:pve1:0006DC5F:0054AA57:6A4E2D01:qmshutdown:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    @DynamicPropertySource
    static void proxmoxProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.proxmox.token-id", () -> "pickle@pve!pickle-api");
        registry.add("pickle.proxmox.token-secret", () -> "wiremock-test-secret");
        registry.add("pickle.proxmox.task-poll-interval", () -> "50ms");
        registry.add("pickle.proxmox.task-poll-timeout", () -> "5s");
    }

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

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

    @Autowired
    private VmPowerJobs vmPowerJobs;

    private User owner;
    private User member;
    private User viewer;
    private User outsider;
    private String ownerToken;
    private String memberToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;
    private int proxmoxVmid;

    @BeforeEach
    void setUp() throws Exception {
        wm.reset();
        owner = ensureUser("vmpow.owner@pusan.ac.kr", "전원소유자");
        member = ensureUser("vmpow.member@pusan.ac.kr", "전원멤버");
        viewer = ensureUser("vmpow.viewer@pusan.ac.kr", "전원뷰어");
        outsider = ensureUser("vmpow.outsider@pusan.ac.kr", "전원외부인");
        ownerToken = jwtService.createAccessToken(owner);
        memberToken = jwtService.createAccessToken(member);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        nodeId = ensureWireMockNode();
        groupId = createTeam("vmpow-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, member.getEmail(), "MEMBER");
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    @Test
    void powerOpsEnforceThePerOpStateMatrix() throws Exception {
        long vmId = createVm(VmStatus.STOPPED);
        Map<String, List<VmStatus>> allowed = Map.of(
                "start", List.of(VmStatus.STOPPED),
                "shutdown", List.of(VmStatus.RUNNING),
                "reboot", List.of(VmStatus.RUNNING),
                "force-stop", List.of(VmStatus.RUNNING, VmStatus.REBOOTING));
        for (Map.Entry<String, List<VmStatus>> entry : allowed.entrySet()) {
            for (VmStatus status : VmStatus.values()) {
                setStatus(vmId, status);
                ResultActions result = mockMvc.perform(post("/api/v1/vms/" + vmId + "/" + entry.getKey())
                        .header("Authorization", "Bearer " + ownerToken));
                if (entry.getValue().contains(status)) {
                    result.andExpect(status().isAccepted())
                            .andExpect(jsonPath("$.message").isNotEmpty());
                } else {
                    result.andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"))
                            .andExpect(jsonPath("$.detail").value(
                                    org.hamcrest.Matchers.containsString(status.name())));
                }
            }
        }
    }

    @Test
    void powerOpsAuthorizeByGroupRole() throws Exception {
        long vmId = createVm(VmStatus.STOPPED);

        // non-member → 404 (existence masked), VIEWER → 403, MEMBER → 202
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isAccepted());

        // unknown VM → 404; unauthenticated → 401
        mockMvc.perform(post("/api/v1/vms/999999/start")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rebootAcceptRecordsIntentAndEnqueuesAfterCommit() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/reboot")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("VM 재부팅 요청을 접수했습니다. 잠시 후 상태가 갱신됩니다."));

        // intent is visible immediately …
        assertThat(statusOf(vmId)).isEqualTo("REBOOTING");
        // … and the durable job landed in the queue after commit
        Long enqueued = jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%VmPowerJobs.reboot(%'",
                Long.class);
        assertThat(enqueued).isPositive();
    }

    @Test
    void startJobTransitionsToRunningAndRecordsTheEvent() {
        long vmId = createVm(VmStatus.STOPPED);
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(qemuPath("status/start")))
                .willReturn(okFixture("40-start")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(START_UPID)))
                .willReturn(okFixture("40-start-status")));

        vmPowerJobs.start(vmId, owner.getId());

        assertThat(statusOf(vmId)).isEqualTo("RUNNING");
        assertThat(statusDetailOf(vmId)).isNull();
        assertThat(lastEvent(vmId)).containsExactly("START", owner.getId(), null);
    }

    @Test
    void shutdownJobRecordsAcpiTimeoutFailureWithoutForceFallback() {
        long vmId = createVm(VmStatus.RUNNING);
        // Real pve1 capture: the guest ignored ACPI until PVE's timeout.
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(qemuPath("status/shutdown")))
                .willReturn(okFixture("61-shutdown")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(SHUTDOWN_UPID)))
                .willReturn(okFixture("61-shutdown-status")));

        vmPowerJobs.shutdown(vmId, owner.getId());

        // status untouched (the poller converges), failure recorded twice over
        assertThat(statusOf(vmId)).isEqualTo("RUNNING");
        assertThat(statusDetailOf(vmId)).contains("종료 실패").contains("timeout");
        var event = lastEvent(vmId);
        assertThat(event.get(0)).isEqualTo("STOP");
        assertThat((String) event.get(2)).contains("종료 실패");
        // contract: the user shutdown op never falls back to a force stop
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath("status/stop"))));
    }

    @Test
    void staleJobRunSkipsQuietly() {
        // enqueued for START but the VM moved on (e.g. deletion) → no-op
        long vmId = createVm(VmStatus.DELETING);
        vmPowerJobs.start(vmId, owner.getId());
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(eventCount(vmId)).isZero();
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath("status/start"))));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String qemuPath(String suffix) {
        return "/api2/json/nodes/" + NODE_NAME + "/qemu/" + proxmoxVmid + "/" + suffix;
    }

    private static String taskStatusPath(String upid) {
        return "/api2/json/nodes/" + NODE_NAME + "/tasks/" + upid + "/status";
    }

    private String statusOf(long vmId) {
        return jdbcTemplate.queryForObject("select status from vms where id = ?", String.class, vmId);
    }

    private String statusDetailOf(long vmId) {
        return jdbcTemplate.queryForObject("select status_detail from vms where id = ?",
                String.class, vmId);
    }

    private List<Object> lastEvent(long vmId) {
        return jdbcTemplate.queryForObject("""
                select type, actor_id, detail from vm_events where vm_id = ? order by id desc limit 1
                """, (rs, i) -> {
            List<Object> row = new java.util.ArrayList<>();
            row.add(rs.getString(1));
            long actorId = rs.getLong(2);
            row.add(rs.wasNull() ? null : actorId);
            row.add(rs.getString(3));
            return row;
        }, vmId);
    }

    private long eventCount(long vmId) {
        return jdbcTemplate.queryForObject("select count(*) from vm_events where vm_id = ?",
                Long.class, vmId);
    }

    private void setStatus(long vmId, VmStatus status) {
        jdbcTemplate.update("update vms set status = ?::vm_status where id = ?", status.name(), vmId);
    }

    /** One shared test node whose api_host points at this class's WireMock. */
    private long ensureWireMockNode() {
        Long existing = jdbcTemplate.query(
                "select id from nodes where name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, NODE_NAME);
        if (existing != null) {
            jdbcTemplate.update("update nodes set api_host = ? where id = ?", wm.apiHost(), existing);
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 8, 16384, 'vmbr2', 'local-lvm') returning id
                """, Long.class, NODE_NAME, wm.apiHost());
    }

    private long createVm(VmStatus status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '전원 제어 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmpow-" + UUID.randomUUID().toString().substring(0, 12);
        proxmoxVmid = VMID_SEQ.incrementAndGet();
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, proxmoxVmid, status.name());
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "전원 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
