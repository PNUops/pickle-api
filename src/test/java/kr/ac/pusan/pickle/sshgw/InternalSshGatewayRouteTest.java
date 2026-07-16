package kr.ac.pusan.pickle.sshgw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * B1 internal SSH-gateway route endpoint (docs/api/internal.md Link 1): the
 * dedicated {@code /internal/**} filter chain (bearer + source-IP allowlist +
 * global rate-limit backstop), the per-client (PROXY-recovered sourceIp) rate
 * limit in the route service, and the four route gates (global kill switch, VM
 * exists / RUNNING / not blocked), plus that every lookup is audited with the
 * reported client source IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InternalSshGatewayRouteTest {

    private static final String TOKEN = "test-sshgw-token";
    private static final String SSHGW_IP = "172.30.1.30";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String OTHER_CLIENT_IP = "198.51.100.9";
    private static final int RATE_LIMIT = 5;
    private static final int GLOBAL_RATE_LIMIT = 12;

    /** Small rate-limit budgets so both limiters can be tripped in short loops. */
    @DynamicPropertySource
    static void sshgwProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.sshgw.rate-limit-per-minute", () -> RATE_LIMIT);
        registry.add("pickle.sshgw.global-rate-limit-per-minute", () -> GLOBAL_RATE_LIMIT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    private long orgId;
    private long templateId;
    private long nodeId;
    private long groupId;
    private long poolId;
    private long requesterId;

    @BeforeEach
    void setUp() {
        // Clean the per-client and global rate windows each test: counters
        // would otherwise bleed across tests.
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'sshgw_route%'");
        setGatewayEnabled(true);

        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'student-vmbr2'",
                Long.class);
        nodeId = ensureNode();
        requesterId = ensureRequester();
        groupId = createGroup();
    }

    @Test
    void runningVmResolvesToItsRouteAndAuditsTheClientIp() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.11", false);

        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ip").value("172.29.4.11"))
                .andExpect(jsonPath("$.port").value(22))
                .andExpect(jsonPath("$.user").value("student"));

        // audited as sshgw.route with the reported client IP (not the sshgw IP)
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select action, ip, detail::text as detail from audit_logs
                 where action = 'sshgw.route' order by id desc limit 1
                """);
        assertThat(row.get("ip")).isEqualTo(CLIENT_IP);
        assertThat((String) row.get("detail")).contains(slug).contains(SSHGW_IP);
    }

    @Test
    void wrongTokenIsRejectedWith401() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.12", false);

        route(slug, CLIENT_IP, SSHGW_IP, "wrong-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void missingTokenIsRejectedWith401() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.13", false);

        mockMvc.perform(routeRequest(slug, CLIENT_IP, SSHGW_IP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void wrongSourceIpIsRejectedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.14", false);

        route(slug, CLIENT_IP, "172.30.1.99", TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unknownSlugIsDeniedWith404() throws Exception {
        route("does-not-exist-" + UUID.randomUUID(), CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SSHGW_ROUTE_NOT_FOUND"));

        assertDenialAudited(CLIENT_IP);
    }

    @Test
    void stoppedVmIsDeniedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.STOPPED, "172.29.4.15", false);

        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_VM_NOT_RUNNING"));

        assertDenialAudited(CLIENT_IP);
    }

    @Test
    void blockedVmIsDeniedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.16", true);

        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_VM_BLOCKED"));
    }

    @Test
    void staleOrReclaimedAllocationIsDeniedWithNoAddress() throws Exception {
        // SSRF guard: a RUNNING VM whose allocation pointer is no longer a live,
        // owned ALLOCATED row must not resolve to any IP.
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.30", false);
        long allocationId = jdbcTemplate.queryForObject(
                "select ip_allocation_id from vms where hostname = ?", Long.class, slug);

        // (a) allocation released (status no longer ALLOCATED) → denied
        jdbcTemplate.update("""
                update ip_allocations set status = 'RELEASED'::allocation_status, released_at = now()
                 where id = ?
                """, allocationId);
        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_ROUTE_NO_ADDRESS"));

        // (b) allocation re-claimed by a DIFFERENT VM (stale pointer) → denied
        String otherSlug = uniqueSlug();
        createVm(otherSlug, VmStatus.RUNNING, "172.29.4.31", false);
        long otherVmId = jdbcTemplate.queryForObject(
                "select id from vms where hostname = ?", Long.class, otherSlug);
        jdbcTemplate.update("""
                update ip_allocations set status = 'ALLOCATED'::allocation_status,
                       released_at = null, vm_id = ? where id = ?
                """, otherVmId, allocationId);
        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_ROUTE_NO_ADDRESS"));
    }

    @Test
    void globallyDisabledGatewayDeniesEverything() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.17", false);
        setGatewayEnabled(false);

        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_GATEWAY_DISABLED"));
    }

    @Test
    void perClientRateLimitDoesNotLockOutOtherClients() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.18", false);

        // One abusive client exhausts ITS bucket …
        for (int i = 0; i < RATE_LIMIT; i++) {
            route(slug, CLIENT_IP, SSHGW_IP, TOKEN).andExpect(status().isOk());
        }
        route(slug, CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        // … and the denial is audited with the abuser's IP and a reason.
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select ip, detail::text as detail from audit_logs
                 where action = 'sshgw.route_denied' order by id desc limit 1
                """);
        assertThat(row.get("ip")).isEqualTo(CLIENT_IP);
        assertThat((String) row.get("detail")).contains("RATE_LIMITED");

        // A DIFFERENT client keeps logging in — no global lockout (the pre-fix
        // peer-keyed bucket 429ed every user once one abuser hit the relay).
        route(slug, OTHER_CLIENT_IP, SSHGW_IP, TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ip").value("172.29.4.18"));
    }

    @Test
    void globalBackstopStillBoundsAFloodAcrossManySourceIps() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.19", false);

        // Distinct sourceIps stay under every per-client bucket, so only the
        // filter's global backstop can (and must) bound the total volume.
        for (int i = 0; i < GLOBAL_RATE_LIMIT; i++) {
            route(slug, "203.0.113." + (100 + i), SSHGW_IP, TOKEN).andExpect(status().isOk());
        }
        route(slug, "203.0.113.250", SSHGW_IP, TOKEN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ResultActions route(String slug, String sourceIp, String peerIp, String token)
            throws Exception {
        return mockMvc.perform(routeRequest(slug, sourceIp, peerIp)
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder routeRequest(
            String slug, String sourceIp, String peerIp) throws Exception {
        return post("/internal/sshgw/route")
                .with(request -> {
                    request.setRemoteAddr(peerIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("slug", slug, "sourceIp", sourceIp)));
    }

    private void assertDenialAudited(String expectedClientIp) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select ip, detail::text as detail from audit_logs
                 where action = 'sshgw.route_denied' order by id desc limit 1
                """);
        assertThat(row.get("ip")).isEqualTo(expectedClientIp);
        assertThat((String) row.get("detail")).contains("reason");
    }

    private void setGatewayEnabled(boolean enabled) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'ssh_gateway_enabled'",
                String.valueOf(enabled));
    }

    private long ensureNode() {
        Long existing = jdbcTemplate.query("select id from nodes where name = 'sshgw-route-test'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage, ip_pool_id)
                values ('sshgw-route-test', 'https://127.0.0.1:8006', 8, 16384, 'vmbr2', 'local-lvm', ?)
                returning id
                """, Long.class, poolId);
    }

    private long ensureRequester() {
        User user = userRepository.findByEmail("sshgw.route@pusan.ac.kr").orElseGet(() -> {
            User u = new User("sshgw.route@pusan.ac.kr", "{test-no-login}", "라우트요청자");
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerifiedAt(Instant.now());
            return userRepository.save(u);
        });
        return user.getId();
    }

    private long createGroup() {
        return jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, '라우트팀', ?) returning id
                """, Long.class, "sshgw-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void createVm(String slug, VmStatus status, String ip, boolean blocked) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, 'SSH 라우트 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, status) values (?, ?::inet, 'ALLOCATED')
                returning id
                """, Long.class, poolId, ip);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status,
                                 ip_allocation_id, ssh_gateway_blocked)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status, ?, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, slug, slug, templateId,
                status.name(), allocationId, blocked);
        jdbcTemplate.update("update ip_allocations set vm_id = ? where id = ?", vmId, allocationId);
    }

    private static String uniqueSlug() {
        return "team-route-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
