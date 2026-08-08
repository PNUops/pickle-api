package kr.ac.pusan.pickle.provisioning;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * VmStatusPoller behavior against WireMock cluster-resources responses (shape
 * from the pve1 capture 03-cluster-resources.json / 52-vm-current.json):
 * RUNNING↔STOPPED mirroring via CAS, the pipeline-race guards (live task,
 * non-pollable statuses), and per-node exception isolation. The recurring
 * trigger itself is JobRunr's; tests call {@link VmStatusPoller#poll()}
 * directly (established style).
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!test",
        "pickle.proxmox.token-secret=wiremock-test-secret"})
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmStatusPollerTest {

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private VmStatusPoller poller;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private ProvisioningTaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long imageId;
    private long requesterId;
    private long groupId;
    private final List<Long> createdNodeIds = new ArrayList<>();

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

    @BeforeEach
    void setUp() {
        wm.reset();
        // The seeded pve1 keeps its real api_host; take it out of the loop so
        // poll cycles in this context never attempt a real connection.
        jdbcTemplate.update("update nodes set status = 'OFFLINE' where name = 'pve1'");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "poll-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    @AfterEach
    void cleanUp() {
        // Park this test's rows so later poll/reconcile cycles (and the other
        // test classes sharing this context) never see them again.
        for (Long nodeId : createdNodeIds) {
            jdbcTemplate.update("update vms set status = 'DELETED' where node_id = ?", nodeId);
            jdbcTemplate.update("update nodes set status = 'OFFLINE' where id = ?", nodeId);
        }
        createdNodeIds.clear();
    }

    @Test
    void mirrorsGuestPowerStateBothWaysAndLeavesMatchingVmsAlone() {
        long nodeId = createNode(wm.apiHost());
        long poweredOff = createVm(nodeId, 101001, "RUNNING");
        long poweredOn = createVm(nodeId, 101002, "STOPPED");
        long unchanged = createVm(nodeId, 101003, "RUNNING");
        stubClusterResources(wm,
                qemu(101001, "stopped", 1, 1024, null),
                qemu(101002, "running", 1, 1024, null),
                qemu(101003, "running", 1, 1024, null),
                lxc(101001, "running")); // lxc noise sharing a vmid must be ignored

        poller.poll();

        Vm off = vmRepository.findById(poweredOff).orElseThrow();
        assertThat(off.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(off.getStatusDetail()).isEqualTo(VmStatusPoller.DETAIL_POWERED_OFF);
        Vm on = vmRepository.findById(poweredOn).orElseThrow();
        assertThat(on.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(on.getStatusDetail()).isEqualTo(VmStatusPoller.DETAIL_POWERED_ON);
        Vm same = vmRepository.findById(unchanged).orElseThrow();
        assertThat(same.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(same.getStatusDetail()).isNull();
    }

    @Test
    void convergesStrandedRebootingVms() {
        // a crashed/failed reboot job leaves REBOOTING behind — the poller
        // converges it to whatever the guest actually reports
        long nodeId = createNode(wm.apiHost());
        long rebootedFine = createVm(nodeId, 101031, "REBOOTING");
        long diedOnReboot = createVm(nodeId, 101032, "REBOOTING");
        stubClusterResources(wm,
                qemu(101031, "running", 1, 1024, null),
                qemu(101032, "stopped", 1, 1024, null));

        poller.poll();

        assertThat(vmRepository.findById(rebootedFine).orElseThrow().getStatus())
                .isEqualTo(VmStatus.RUNNING);
        assertThat(vmRepository.findById(diedOnReboot).orElseThrow().getStatus())
                .isEqualTo(VmStatus.STOPPED);
    }

    @Test
    void skipsVmsOwnedByThePipeline() {
        long nodeId = createNode(wm.apiHost());
        long withLiveTask = createVm(nodeId, 101011, "RUNNING");
        taskRepository.save(new ProvisioningTask(withLiveTask, ProvisioningTaskKind.DELETE)); // PENDING = live
        long creating = createVm(nodeId, 101012, "CREATING");
        long needsAdmin = createVm(nodeId, 101013, "NEEDS_ADMIN");
        // Proxmox reports them all stopped — none of them may move.
        stubClusterResources(wm,
                qemu(101011, "stopped", 1, 1024, null),
                qemu(101012, "stopped", 1, 1024, null),
                qemu(101013, "stopped", 1, 1024, null));

        poller.poll();

        assertThat(vmRepository.findById(withLiveTask).orElseThrow().getStatus())
                .isEqualTo(VmStatus.RUNNING);
        assertThat(vmRepository.findById(creating).orElseThrow().getStatus())
                .isEqualTo(VmStatus.CREATING);
        assertThat(vmRepository.findById(needsAdmin).orElseThrow().getStatus())
                .isEqualTo(VmStatus.NEEDS_ADMIN);
    }

    @Test
    void oneBrokenNodeDoesNotStopTheCycle() {
        // Created first → iterated first: connection refused, must be swallowed.
        long deadNodeId = createNode("http://127.0.0.1:1");
        long onDeadNode = createVm(deadNodeId, 101021, "RUNNING");
        long nodeId = createNode(wm.apiHost());
        long onGoodNode = createVm(nodeId, 101022, "RUNNING");
        stubClusterResources(wm, qemu(101022, "stopped", 1, 1024, null));

        assertThatCode(() -> poller.poll()).doesNotThrowAnyException();

        assertThat(vmRepository.findById(onDeadNode).orElseThrow().getStatus())
                .isEqualTo(VmStatus.RUNNING);
        assertThat(vmRepository.findById(onGoodNode).orElseThrow().getStatus())
                .isEqualTo(VmStatus.STOPPED);
    }

    // --- fixtures -------------------------------------------------------------

    /** MAINTENANCE so ACTIVE-capacity math elsewhere never counts test nodes. */
    private long createNode(String apiHost) {
        String name = "poll-node-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 'MAINTENANCE', 16, 32768, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, name, apiHost);
        createdNodeIds.add(id);
        return id;
    }

    /** Minimal request→vm FK chain with a Proxmox identity and explicit status. */
    private long createVm(long nodeId, int proxmoxVmid, String status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '폴러 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, requesterId, imageId);
        String hostname = "poll-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                imageId, proxmoxVmid, status);
    }

    // --- WireMock stubbing (shared with DriftReconcilerTest) --------------------

    static void stubClusterResources(ProxmoxWireMockSupport wm, String... entries) {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .withQueryParam("type", equalTo("vm"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":[" + String.join(",", entries) + "]}")));
    }

    /** One qemu entry in the real capture's shape; {@code memoryMb} → maxmem bytes. */
    static String qemu(int vmid, String status, int maxcpu, long memoryMb, String tags) {
        String tagsField = tags == null ? "" : ",\"tags\":\"%s\"".formatted(tags);
        return ("{\"vmid\":%d,\"name\":\"vm-%d\",\"status\":\"%s\",\"type\":\"qemu\","
                + "\"node\":\"pve1\",\"maxmem\":%d,\"maxcpu\":%d,\"maxdisk\":4831838208,"
                + "\"template\":0%s}")
                .formatted(vmid, vmid, status, memoryMb * 1024 * 1024, maxcpu, tagsField);
    }

    static String lxc(int vmid, String status) {
        return ("{\"vmid\":%d,\"name\":\"ct-%d\",\"status\":\"%s\",\"type\":\"lxc\","
                + "\"node\":\"pve1\",\"maxmem\":536870912,\"maxcpu\":1,\"maxdisk\":8350298112}")
                .formatted(vmid, vmid, status);
    }
}
