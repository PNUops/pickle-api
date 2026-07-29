package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.hamcrest.Matchers;
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
 * {@code GET /admin/nodes} per contract: SYS_ADMIN only, NodeSummary fields
 * with exact aggregate numbers (running count and vCPU/memory sums over
 * non-DELETED/ERROR VMs, 2-decimal ratios, settings thresholds) and the
 * IpPoolSummary occupancy (usable minus reserved/gateway, ALLOCATED, and
 * quarantined RELEASED rows). A dedicated MAINTENANCE node with its own pool
 * keeps the numbers deterministic in the shared test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminNodesTest {

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

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
    }

    @Test
    void sysAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nodes").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/admin/nodes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsSeededNodeWithStaticCapacityAndPool() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nodes").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'pve1')].cpuThreads").value(Matchers.hasItem(40)))
                .andExpect(jsonPath("$[?(@.name == 'pve1')].memoryMb").value(Matchers.hasItem(79872)))
                .andExpect(jsonPath("$[?(@.name == 'pve1')].vmBridge").value(Matchers.hasItem("vmbr2")))
                .andExpect(jsonPath("$[?(@.name == 'pve1')].storage").value(Matchers.hasItem("local-lvm")))
                .andExpect(jsonPath("$[?(@.name == 'pve1')].ipPool.cidr")
                        .value(Matchers.hasItem("172.29.0.0/16")));
    }

    @Test
    void aggregatesAllocationsRatiosThresholdsAndPoolOccupancy() throws Exception {
        // Pool 10.99.0.0/28: 14 usable, minus 2 reserved (.2/.3) minus the
        // gateway (.1) = 11 candidates. 2 ALLOCATED + 1 RELEASED still in
        // quarantine + 1 RELEASED past quarantine → free = 11 - 2 - 1 = 8.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long poolId = jdbcTemplate.queryForObject("""
                insert into ip_pools (name, cidr, gateway, dns, reserved_ranges)
                values (?, '10.99.0.0/28'::cidr, '10.99.0.1'::inet, '["8.8.8.8"]'::jsonb,
                        '[{"from": "10.99.0.2", "to": "10.99.0.3"}]'::jsonb)
                returning id
                """, Long.class, "nodesum-" + suffix);
        jdbcTemplate.update("""
                insert into ip_allocations (pool_id, ip, status, released_at) values
                    (?, '10.99.0.4'::inet, 'ALLOCATED', null),
                    (?, '10.99.0.5'::inet, 'ALLOCATED', null),
                    (?, '10.99.0.6'::inet, 'RELEASED', now()),
                    (?, '10.99.0.7'::inet, 'RELEASED', now() - interval '48 hours')
                """, poolId, poolId, poolId, poolId);
        // MAINTENANCE so ACTIVE-capacity math elsewhere never counts this node.
        long nodeId = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb,
                                   vm_bridge, storage, ip_pool_id)
                values (?, 'https://127.0.0.1:1', 'MAINTENANCE', 16, 32768, 'vmbr9', 'big-lvm', ?)
                returning id
                """, Long.class, "nodesum-" + suffix, poolId);
        // 1 RUNNING + 1 STOPPED count toward the sums; ERROR does not.
        createVm(nodeId, "RUNNING", 2, 2048);
        createVm(nodeId, "STOPPED", 2, 2048);
        createVm(nodeId, "ERROR", 4, 4096);

        String byId = "$[?(@.id == %d)]".formatted(nodeId);
        mockMvc.perform(get("/api/v1/admin/nodes").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId + ".status").value(Matchers.hasItem("MAINTENANCE")))
                .andExpect(jsonPath(byId + ".cpuThreads").value(Matchers.hasItem(16)))
                .andExpect(jsonPath(byId + ".memoryMb").value(Matchers.hasItem(32768)))
                .andExpect(jsonPath(byId + ".vmBridge").value(Matchers.hasItem("vmbr9")))
                .andExpect(jsonPath(byId + ".storage").value(Matchers.hasItem("big-lvm")))
                .andExpect(jsonPath(byId + ".runningVms").value(Matchers.hasItem(1)))
                .andExpect(jsonPath(byId + ".allocatedVcpu").value(Matchers.hasItem(4)))
                .andExpect(jsonPath(byId + ".allocatedMemoryMb").value(Matchers.hasItem(4096)))
                // 4/16 = 0.25 and 4096/32768 = 0.125 → 0.13 (2-decimal rounding)
                .andExpect(jsonPath(byId + ".cpuOvercommitRatio").value(Matchers.hasItem(0.25)))
                .andExpect(jsonPath(byId + ".memoryAllocRatio").value(Matchers.hasItem(0.13)))
                // V3-seeded operator-tunable thresholds
                .andExpect(jsonPath(byId + ".cpuWarnThreshold").value(Matchers.hasItem(3.0)))
                .andExpect(jsonPath(byId + ".memoryWarnThreshold").value(Matchers.hasItem(0.8)))
                .andExpect(jsonPath(byId + ".ipPool.id")
                        .value(Matchers.hasItem(Math.toIntExact(poolId))))
                .andExpect(jsonPath(byId + ".ipPool.cidr").value(Matchers.hasItem("10.99.0.0/28")))
                .andExpect(jsonPath(byId + ".ipPool.allocatedCount").value(Matchers.hasItem(2)))
                .andExpect(jsonPath(byId + ".ipPool.freeCount").value(Matchers.hasItem(8)));
    }

    /** Minimal request→vm FK chain on the given node (dedicated group per VM). */
    private void createVm(long nodeId, String status, int vcpu, int memoryMb) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "nodesum-" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '노드 집계 테스트', ?, ?, ?, 10)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId, vcpu, memoryMb);
        jdbcTemplate.update("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 10, ?::vm_status)
                """, nodeId, groupId, orgId, requestId, slug, slug, templateId, vcpu, memoryMb, status);
    }
}
