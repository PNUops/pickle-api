package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.UserRepository;
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
 * {@code GET /admin/ip-allocations} per contract: SYS_ADMIN only, newest
 * allocation first, poolId/status filters, pool/VM join fields, RELEASED rows
 * kept as history. Assertions are per-id (shared database).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminIpAllocationsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String orgAdminToken;
    private long poolId;
    private long otherPoolId;
    private long vmId;
    private long allocated;
    private long released;
    private long inOtherPool;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        poolId = jdbcTemplate.queryForObject(
                "select id from ip_pools where name = 'guest-private'", Long.class);
        otherPoolId = createPool();
        vmId = createVm();
        allocated = createAllocation(poolId, vmId, "ALLOCATED");
        released = createAllocation(poolId, vmId, "RELEASED");
        inOtherPool = createAllocation(otherPoolId, null, "ALLOCATED");
    }

    @Test
    void listsAllocationsWithPoolAndVmContext() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ip-allocations")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/ip-allocations")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(allocated)).exists())
                .andExpect(jsonPath(byId(released)).exists())
                .andExpect(jsonPath(byId(inOtherPool)).exists())
                .andExpect(jsonPath(byId(allocated) + ".poolId").value((int) poolId))
                .andExpect(jsonPath(byId(allocated) + ".poolName").value("guest-private"))
                .andExpect(jsonPath(byId(allocated) + ".ip").isNotEmpty())
                .andExpect(jsonPath(byId(allocated) + ".vmId").value((int) vmId))
                .andExpect(jsonPath(byId(allocated) + ".vmName").isNotEmpty())
                .andExpect(jsonPath(byId(allocated) + ".hostname").isNotEmpty())
                .andExpect(jsonPath(byId(allocated) + ".status").value("ALLOCATED"))
                .andExpect(jsonPath(byId(allocated) + ".allocatedAt").isNotEmpty())
                .andExpect(jsonPath(byId(released) + ".status").value("RELEASED"))
                .andExpect(jsonPath(byId(released) + ".releasedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.id == %d && @.vmId == null)]"
                        .formatted(inOtherPool)).exists());
    }

    @Test
    void filtersByPoolAndStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ip-allocations?poolId=" + otherPoolId)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(inOtherPool)).exists())
                .andExpect(jsonPath(byId(allocated)).doesNotExist())
                .andExpect(jsonPath(byId(released)).doesNotExist());

        mockMvc.perform(get("/api/v1/admin/ip-allocations?poolId=%d&status=RELEASED"
                        .formatted(poolId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(released)).exists())
                .andExpect(jsonPath(byId(allocated)).doesNotExist());

        mockMvc.perform(get("/api/v1/admin/ip-allocations?size=0")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent());
    }

    // --- fixtures ---------------------------------------------------------------

    private static String byId(long id) {
        return "$.content[?(@.id == %d)]".formatted(id);
    }

    private long createPool() {
        String name = "adip-pool-" + UUID.randomUUID().toString().substring(0, 8);
        int thirdOctet = ThreadLocalRandom.current().nextInt(0, 256);
        return jdbcTemplate.queryForObject("""
                insert into ip_pools (name, cidr, gateway)
                values (?, ?::cidr, ?::inet)
                returning id
                """, Long.class, name, "10.99.%d.0/24".formatted(thirdOctet),
                "10.99.%d.1".formatted(thirdOctet));
    }

    private long createAllocation(long pool, Long vm, String status) {
        // high third octet: clear of the sequential low addresses the real
        // pipeline tests allocate (unique index on ip is global)
        String ip = "172.29.%d.%d".formatted(ThreadLocalRandom.current().nextInt(100, 250),
                ThreadLocalRandom.current().nextInt(1, 255));
        return jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status, released_at)
                values (?, ?::inet, ?, ?::allocation_status,
                        case when ? = 'RELEASED' then now() end)
                returning id
                """, Long.class, pool, ip, vm, status, status);
    }

    private long createVm() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        String slug = "adip-" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, 'IP 할당 테스트', ?, 2, 2048, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "adip-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'STOPPED'::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }
}
