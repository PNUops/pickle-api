package kr.ac.pusan.pickle.provisioning;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static kr.ac.pusan.pickle.provisioning.VmStatusPollerTest.qemu;
import static kr.ac.pusan.pickle.provisioning.VmStatusPollerTest.stubClusterResources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DriftReconciler behavior for the three drift classes —
 * missing-in-Proxmox → NEEDS_ADMIN, unmanaged pickle-tagged guest → WARN log
 * only (never any write to Proxmox), spec mismatch → informational
 * status_detail — plus the live-task guard and per-node exception isolation.
 * Tests call {@link DriftReconciler#reconcile()} directly (established style).
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!test",
        "pickle.proxmox.token-secret=wiremock-test-secret"})
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DriftReconcilerTest {

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private DriftReconciler reconciler;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private ProvisioningTaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long imageId;
    private long requesterId;
    private long workspaceId;
    private final List<Long> createdNodeIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> logAppender;

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
        jdbcTemplate.update("update nodes set status = 'OFFLINE' where name = 'pve1'");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "drift-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(DriftReconciler.class)).addAppender(logAppender);
    }

    @AfterEach
    void cleanUp() {
        ((Logger) LoggerFactory.getLogger(DriftReconciler.class)).detachAppender(logAppender);
        for (Long nodeId : createdNodeIds) {
            jdbcTemplate.update("update vms set status = 'DELETED' where node_id = ?", nodeId);
            jdbcTemplate.update("update nodes set status = 'OFFLINE' where id = ?", nodeId);
        }
        createdNodeIds.clear();
    }

    @Test
    void missingVmIsParkedNeedsAdminUnlessThePipelineOwnsIt() {
        long nodeId = createNode(wm.apiHost());
        long missing = createVm(nodeId, 102001, "RUNNING", 1, 1024);
        long missingButOwned = createVm(nodeId, 102002, "DELETING", 1, 1024);
        taskRepository.save(new ProvisioningTask(missingButOwned, ProvisioningTaskKind.DELETE));
        long present = createVm(nodeId, 102003, "RUNNING", 1, 1024);
        stubClusterResources(wm, qemu(102003, "running", 1, 1024, "pickle"));

        reconciler.reconcile();

        Vm parked = vmRepository.findById(missing).orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
        assertThat(parked.getStatusDetail()).isEqualTo(DriftReconciler.DETAIL_MISSING);
        // live DELETE task → mid-pipeline disappearance is expected, not drift
        assertThat(vmRepository.findById(missingButOwned).orElseThrow().getStatus())
                .isEqualTo(VmStatus.DELETING);
        Vm intact = vmRepository.findById(present).orElseThrow();
        assertThat(intact.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(intact.getStatusDetail()).isNull();
    }

    @Test
    void unmanagedPickleTaggedVmIsOnlyLoggedAndNeverTouched() {
        long nodeId = createNode(wm.apiHost());
        long known = createVm(nodeId, 102011, "RUNNING", 1, 1024);
        stubClusterResources(wm,
                qemu(102011, "running", 1, 1024, "pickle"),
                qemu(102999, "running", 2, 2048, "dev;pickle"), // unmanaged + tagged → WARN
                qemu(103000, "running", 2, 2048, "dev")); // untagged stranger → ignored
        long vmRowsBefore = jdbcTemplate.queryForObject("select count(*) from vms", Long.class);

        reconciler.reconcile();

        List<String> warnings = logAppender.list.stream()
                .filter(event -> event.getLevel().toString().equals("WARN"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnings).anySatisfy(message -> assertThat(message).contains("102999"));
        assertThat(warnings).noneSatisfy(message -> assertThat(message).contains("103000"));
        // absolutely no writes: no DB row appeared, and Proxmox saw GETs only
        assertThat(jdbcTemplate.queryForObject("select count(*) from vms", Long.class))
                .isEqualTo(vmRowsBefore);
        assertThat(vmRepository.findById(known).orElseThrow().getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(wm.server().getAllServeEvents())
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getRequest().getMethod().getName())
                        .isEqualTo("GET"));
        wm.server().verify(getRequestedFor(urlMatching("/api2/json/cluster/resources.*")));
    }

    @Test
    void specMismatchSetsInformationalFlagWithoutTransitionAndClearsWhenResolved() {
        long nodeId = createNode(wm.apiHost());
        long vmId = createVm(nodeId, 102021, "RUNNING", 2, 2048);
        stubClusterResources(wm, qemu(102021, "running", 4, 4096, "pickle"));

        reconciler.reconcile();

        Vm flagged = vmRepository.findById(vmId).orElseThrow();
        assertThat(flagged.getStatus()).isEqualTo(VmStatus.RUNNING); // no transition
        assertThat(flagged.getStatusDetail())
                .isEqualTo(DriftReconciler.SPEC_DRIFT_PREFIX + ": Proxmox 4vCPU/4096MB ≠ DB 2vCPU/2048MB");

        // the operator fixed the guest → the informational flag clears again
        wm.reset();
        stubClusterResources(wm, qemu(102021, "running", 2, 2048, "pickle"));
        reconciler.reconcile();
        Vm cleared = vmRepository.findById(vmId).orElseThrow();
        assertThat(cleared.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(cleared.getStatusDetail()).isNull();
    }

    @Test
    void oneBrokenNodeDoesNotStopTheCycle() {
        long deadNodeId = createNode("http://127.0.0.1:1");
        long onDeadNode = createVm(deadNodeId, 102031, "RUNNING", 1, 1024);
        long nodeId = createNode(wm.apiHost());
        long missing = createVm(nodeId, 102032, "RUNNING", 1, 1024);
        stubClusterResources(wm); // empty cluster → 102032 is missing

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();

        // the dead node's VM is untouched (no false "missing" from a dead API)
        assertThat(vmRepository.findById(onDeadNode).orElseThrow().getStatus())
                .isEqualTo(VmStatus.RUNNING);
        assertThat(vmRepository.findById(missing).orElseThrow().getStatus())
                .isEqualTo(VmStatus.NEEDS_ADMIN);
    }

    @Test
    void protectionDriftIsFlaggedForAnyVmWhenPveFlagClearedAndResolvesWhenRestored() {
        long nodeId = createNode(wm.apiHost());
        String nodeName = jdbcTemplate.queryForObject("select name from nodes where id = ?",
                String.class, nodeId);
        long vmId = createVm(nodeId, 102501, "RUNNING", 1, 1024);
        // Always-on invariant: no vm_settings row exists at all, yet a cleared
        // PVE protection flag (out-of-band qm set --protection 0) is drift.
        stubClusterResources(wm, qemu(102501, "running", 1, 1024, "pickle"));
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                "/api2/json/nodes/" + nodeName + "/qemu/102501/config"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":{\"cores\":1}}")));

        reconciler.reconcile();

        Long findings = jdbcTemplate.queryForObject("""
                select count(*) from drift_findings
                 where vm_id = ? and kind = 'SPEC_MISMATCH' and status = 'OPEN'
                   and summary like '%보호 플래그 불일치%'
                """, Long.class, vmId);
        assertThat(findings).isEqualTo(1L);

        // the operator re-armed the flag → the finding auto-resolves
        wm.reset();
        stubClusterResources(wm, qemu(102501, "running", 1, 1024, "pickle"));
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                "/api2/json/nodes/" + nodeName + "/qemu/102501/config"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":{\"cores\":1,\"protection\":1}}")));
        reconciler.reconcile();
        Long open = jdbcTemplate.queryForObject("""
                select count(*) from drift_findings
                 where vm_id = ? and kind = 'SPEC_MISMATCH' and status = 'OPEN'
                   and summary like '%보호 플래그 불일치%'
                """, Long.class, vmId);
        assertThat(open).isZero();
    }

    // --- fixtures (same shape as VmStatusPollerTest) ----------------------------

    private long createNode(String apiHost) {
        String name = "drift-node-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 'MAINTENANCE', 16, 32768, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, name, apiHost);
        createdNodeIds.add(id);
        return id;
    }

    private long createVm(long nodeId, int proxmoxVmid, String status, int vcpu, int memoryMb) {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "드리프트 테스트", imageId, vcpu, memoryMb, 10);
        String hostname = "drift-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, vcpu, memoryMb, proxmoxVmid, status);
    }

    @Test
    void anotherProducersFindingSurvivesThisReconcilersCycle() {
        // Auto-resolve authority follows production: this reconciler never
        // observes an OpenRouter key, so a foreign-kind finding must not be
        // "no longer observed" to it. Live defect on 2026-08-24 — every
        // OPENROUTER_ORPHAN the other reconciler raised was silently resolved
        // within ten minutes.
        long nodeId = createNode(wm.apiHost());
        stubClusterResources(wm);
        jdbcTemplate.update("""
                insert into drift_findings (kind, summary, detail, dedup_key,
                                            first_seen_at, last_seen_at)
                values ('OPENROUTER_ORPHAN', 'foreign finding', '{}', 'foreign-hash',
                        now(), now())
                """);
        assertThat(nodeId).isPositive();

        reconciler.reconcile();

        Long open = jdbcTemplate.queryForObject("""
                select count(*) from drift_findings
                 where kind = 'OPENROUTER_ORPHAN' and dedup_key = 'foreign-hash'
                   and status = 'OPEN'
                """, Long.class);
        assertThat(open).isEqualTo(1L);
        jdbcTemplate.update(
                "delete from drift_findings where dedup_key = 'foreign-hash'");
    }
}
