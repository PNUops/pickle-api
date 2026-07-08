package kr.ac.pusan.pickle.ipam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IPAM guarantees per docs/plan/03: sequential allocation skipping gateway
 * and reserved ranges, race-safe concurrent allocation (unique ip index),
 * idempotent release, the 24 h quarantine on released addresses, and an
 * explicit exhaustion failure. Runs against the V5 schema on embedded PG.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class IpamServiceTest {

    @Autowired
    private IpamService ipamService;

    @Autowired
    private IpPoolRepository poolRepository;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long nodeId;
    private long templateId;
    private long requesterId;
    private long groupId;

    @BeforeEach
    void setUp() {
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        templateId = jdbcTemplate.queryForObject(
                "select min(id) from vm_templates", Long.class);
        requesterId = jdbcTemplate.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        String slug = "ipam-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id
                """, Long.class, slug, slug);
    }

    @Test
    void v5SeedWiresPoolNodeAndQuarantineSetting() {
        IpPool pool = poolRepository.findByName("student-vmbr2").orElseThrow();
        assertThat(pool.getCidr()).isEqualTo("172.29.0.0/16");
        assertThat(pool.getGateway()).isEqualTo("172.29.0.1");
        assertThat(pool.getDns()).contains("8.8.8.8");
        assertThat(pool.getReservedRanges()).contains("172.29.0.255").contains("172.29.255.0");

        Long nodePoolId = jdbcTemplate.queryForObject(
                "select ip_pool_id from nodes where name = 'pve1'", Long.class);
        assertThat(nodePoolId).isEqualTo(pool.getId());

        assertThat(settingsService.integer(SettingsService.IP_QUARANTINE_HOURS, 0)).isEqualTo(24);
    }

    @Test
    void allocatesSequentiallySkippingGatewayAndReservedRanges() {
        long poolId = createPool("ipam-reserved", "10.91.0.0/28", "10.91.0.1",
                "[{\"from\": \"10.91.0.2\", \"to\": \"10.91.0.4\"}]");

        IpAllocation first = ipamService.allocate(poolId, createVm());
        IpAllocation second = ipamService.allocate(poolId, createVm());
        IpAllocation third = ipamService.allocate(poolId, createVm());

        // .0 network, .1 gateway, .2-.4 reserved → allocation starts at .5
        assertThat(first.getIp()).isEqualTo("10.91.0.5");
        assertThat(second.getIp()).isEqualTo("10.91.0.6");
        assertThat(third.getIp()).isEqualTo("10.91.0.7");
        assertThat(first.getPoolId()).isEqualTo(poolId);
        assertThat(first.getStatus()).isEqualTo(AllocationStatus.ALLOCATED);
        assertThat(first.getVmId()).isNotNull();
        assertThat(first.getAllocatedAt()).isNotNull();
        assertThat(first.getReleasedAt()).isNull();
    }

    @Test
    void releasedIpStaysQuarantinedThenBecomesReusable() {
        // /30 with .1 as gateway leaves exactly one usable address: .2
        long poolId = createPool("ipam-quarantine", "10.92.0.0/30", "10.92.0.1", "[]");
        long firstVm = createVm();
        IpAllocation allocation = ipamService.allocate(poolId, firstVm);
        assertThat(allocation.getIp()).isEqualTo("10.92.0.2");

        ipamService.release(allocation.getId());
        var afterFirstRelease = jdbcTemplate.queryForMap(
                "select status, released_at from ip_allocations where id = ?", allocation.getId());
        assertThat(afterFirstRelease.get("status")).hasToString("RELEASED");
        assertThat(afterFirstRelease.get("released_at")).isNotNull();

        // idempotent: repeat release neither fails nor resets the quarantine clock
        ipamService.release(allocation.getId());
        var afterSecondRelease = jdbcTemplate.queryForMap(
                "select status, released_at from ip_allocations where id = ?", allocation.getId());
        assertThat(afterSecondRelease.get("released_at")).isEqualTo(afterFirstRelease.get("released_at"));

        // still inside the 24 h quarantine → the only address is unusable
        assertThatThrownBy(() -> ipamService.allocate(poolId, createVm()))
                .isInstanceOf(IpPoolExhaustedException.class);

        // age the release past the quarantine window → reusable again
        jdbcTemplate.update("update ip_allocations set released_at = now() - interval '25 hours'"
                + " where id = ?", allocation.getId());
        long secondVm = createVm();
        IpAllocation reused = ipamService.allocate(poolId, secondVm);
        assertThat(reused.getId()).isEqualTo(allocation.getId());
        assertThat(reused.getIp()).isEqualTo("10.92.0.2");
        assertThat(reused.getStatus()).isEqualTo(AllocationStatus.ALLOCATED);
        assertThat(reused.getVmId()).isEqualTo(secondVm);
        assertThat(reused.getReleasedAt()).isNull();
    }

    @Test
    void exhaustedPoolThrowsExplicitly() {
        // /29 minus network/broadcast/gateway → five usable addresses (.2-.6)
        long poolId = createPool("ipam-exhaust", "10.93.0.0/29", "10.93.0.1", "[]");
        Set<String> ips = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ips.add(ipamService.allocate(poolId, createVm()).getIp());
        }
        assertThat(ips).hasSize(5).allMatch(ip -> ip.startsWith("10.93.0."));

        assertThatThrownBy(() -> ipamService.allocate(poolId, createVm()))
                .isInstanceOf(IpPoolExhaustedException.class)
                .hasMessageContaining("ipam-exhaust");
    }

    @Test
    void concurrentAllocationsNeverHandOutTheSameIp() throws Exception {
        int threads = 16;
        long poolId = createPool("ipam-race", "10.94.0.0/24", "10.94.0.1", "[]");
        List<Long> vmIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            vmIds.add(createVm());
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (long vmId : vmIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return ipamService.allocate(poolId, vmId).getIp();
                }));
            }
            start.countDown();
            Set<String> ips = new HashSet<>();
            for (Future<String> future : futures) {
                ips.add(future.get());
            }
            // every thread got an address and no two threads share one
            assertThat(ips).hasSize(threads);
        } finally {
            executor.shutdownNow();
        }

        Integer allocated = jdbcTemplate.queryForObject(
                "select count(*) from ip_allocations where pool_id = ?", Integer.class, poolId);
        assertThat(allocated).isEqualTo(threads);
    }

    private long createPool(String name, String cidr, String gateway, String reservedRanges) {
        return jdbcTemplate.queryForObject("""
                insert into ip_pools (name, cidr, gateway, reserved_ranges)
                values (?, ?::cidr, ?::inet, ?::jsonb)
                returning id
                """, Long.class, name, cidr, gateway, reservedRanges);
    }

    /** Minimal request→vm graph; ip_allocations.vm_id has a real FK to vms. */
    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, 'IPAM 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "ipam-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }
}
