package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmCloneReservationConcurrencyTest {

    @Autowired
    private VmCloneReservationService reservations;

    @Autowired
    private OsImageRepository images;

    @Autowired
    private VmRepository vms;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void concurrentApprovalsCannotBothConsumeOnePreparedNodeBudget() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long poolId = jdbc.queryForObject("select min(id) from ip_pools", Long.class);
        long nodeId = jdbc.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, labels,
                                   vm_bridge, storage, ip_pool_id, disk_capacity_gb)
                values (?, 'https://127.0.0.1:8006', 'ACTIVE', 3, 2048,
                        cast(? as jsonb), 'vmbr2', 'local-lvm', ?, 30)
                returning id
                """, Long.class, "reserve-" + suffix,
                """
                {"placement_capacity":{"schema_version":1,"measured_at":"2026-09-18T00:00:00Z",
                "physical":{"cpu_threads":3,"memory_mb":3072,"disk_gb":30},
                "reserved":{"cpu_threads":1,"memory_mb":1024,"disk_gb":10},
                "allocatable":{"cpu_threads":2,"memory_mb":2048,"disk_gb":20}},
                "vm_nic_requirements":{"schema_version":1,"mtu":1370,"firewall":true}}
                """, poolId);
        OsImage image = images.saveAndFlush(new OsImage("reserve-os-" + suffix, "Concurrent OS",
                "ubuntu", "24.04", "ubuntu", 990100, nodeId, 1, 10,
                CatalogStatus.ACTIVE, null));
        UUID nodePublicId = jdbc.queryForObject(
                "select public_id from nodes where id = ?", UUID.class, nodeId);
        long orgId = SeedFixtures.seedOrgId(jdbc);
        long requesterId = SeedFixtures.orgadminId(jdbc);
        long[] workspaces = {workspace("reserve-a-" + suffix), workspace("reserve-b-" + suffix)};
        long[] requests = {
                RequestFixtures.insertVmRequest(jdbc, workspaces[0], orgId, requesterId,
                        "동시 승인 A", image.getId(), 2, 2048, 20),
                RequestFixtures.insertVmRequest(jdbc, workspaces[1], orgId, requesterId,
                        "동시 승인 B", image.getId(), 2, 2048, 20)
        };
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> reserve(start, image, nodePublicId, requests[0],
                            workspaces[0], orgId, "a-" + suffix)),
                    executor.submit(() -> reserve(start, image, nodePublicId, requests[1],
                            workspaces[1], orgId, "b-" + suffix)));
            start.countDown();
            assertThat(results.stream().map(this::get).toList())
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(vms.sumActiveByNodeId(nodeId, kr.ac.pusan.pickle.vm.VmStatus.DELETED).getVcpu())
                .isEqualTo(2);
    }

    private boolean reserve(CountDownLatch start, OsImage image, UUID nodePublicId,
            long requestId, long workspaceId, long orgId, String hostname) throws Exception {
        start.await();
        try {
            transactions.executeWithoutResult(ignored -> {
                VmCloneReservationService.Reservation reservation = reservations.reserve(
                        image.getPublicId(), nodePublicId,
                        new NodePlacementBudget.VmPlacementResources(2, 2048, 20));
                Vm vm = new Vm(reservation.node().getId(), workspaceId, orgId, requestId,
                        hostname, hostname, reservation.canonical().getId(), "ubuntu",
                        2, 2048, 20, LocalDate.now(), LocalDate.now().plusMonths(1));
                vm.pinClone(reservation.pin());
                vms.saveAndFlush(vm);
            });
            return true;
        } catch (VmCloneReservationService.NoCapacityException noCapacity) {
            return false;
        }
    }

    private long workspace(String name) {
        return jdbc.queryForObject(
                "insert into workspaces (kind, name) values ('PROJECT', ?) returning id",
                Long.class, name);
    }

    private boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
