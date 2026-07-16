package kr.ac.pusan.pickle.provisioning;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.fixture;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.jsonFixture;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fault-injection tests of the M3 provision pipeline (docs/plan/03 M3 gate)
 * against WireMock serving the captured pve1 responses. The JobRunr background
 * server stays off (application-test.yml), so each "run" is a direct
 * {@code provisionVm} call — the retry runs a live system would get from the
 * self-scheduled backoff job are simulated by calling again, which exercises
 * exactly the same RETRYING → RUNNING resume path.
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!pickle-api",
        "pickle.proxmox.token-secret=wiremock-test-secret",
        "pickle.proxmox.connect-timeout=1s",
        "pickle.proxmox.read-timeout=2s",
        "pickle.proxmox.task-poll-interval=50ms",
        "pickle.proxmox.task-poll-timeout=1s"
})
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ProvisionPipelineTest {

    private static final String NODE = "pve1";
    private static final String CLONE_UPID =
            "UPID:pve1:0006D77B:00548A83:6A4E2CB0:qmclone:9000:pickle@pve!pickle-api:";
    private static final String RESIZE_UPID =
            "UPID:pve1:0006D8A5:005490E8:6A4E2CC0:resize:102:pickle@pve!pickle-api:";
    private static final String START_UPID =
            "UPID:pve1:0006D8F7:005491B8:6A4E2CC2:qmstart:102:pickle@pve!pickle-api:";
    private static final String DELETE_UPID =
            "UPID:pve1:0006E08C:0054C47B:6A4E2D44:qmdestroy:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private ProvisionVmJob job;

    @Autowired
    private ProvisioningTaskRepository taskRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private IpamService ipamService;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private kr.ac.pusan.pickle.notification.NotificationDispatchJob notificationDispatchJob;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long nodeId;
    private long poolId;
    private long templateId;
    private long adminUserId;
    private long groupId;

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
        mockMailSender.clear();
        // point the seeded node at WireMock — the pipeline reads api_host per run
        jdbc.update("update nodes set api_host = ? where name = ?", wm.apiHost(), NODE);
        orgId = jdbc.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = jdbc.queryForObject("select id from nodes where name = ?", Long.class, NODE);
        poolId = jdbc.queryForObject("select ip_pool_id from nodes where id = ?", Long.class, nodeId);
        templateId = jdbc.queryForObject("select min(id) from vm_templates", Long.class);
        adminUserId = jdbc.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        String slug = "pipe-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbc.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    // ── ① clone keeps failing → retries exhausted → full compensation ────────

    @Test
    void cloneFailureExhaustsRetriesAndCompensates() {
        long vmId = createVm();
        int vmid = 111;
        stubNextId(vmid);
        // Scenario: 4 failing clone calls; after the 4th, the half-created VM
        // "exists" so the compensation existence check finds and destroys it.
        for (int attempt = 0; attempt < 4; attempt++) {
            String from = attempt == 0 ? Scenario.STARTED : "failed-" + attempt;
            wm.server().stubFor(post(urlPathEqualTo(qemuPath(9000) + "/clone"))
                    .inScenario("clone-fail")
                    .whenScenarioStateIs(from)
                    .willReturn(jsonFixture(500, "11-clone-dup-error"))
                    .willSetStateTo("failed-" + (attempt + 1)));
            wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                    .inScenario("clone-fail")
                    .whenScenarioStateIs(from)
                    .willReturn(okFixture("03-cluster-resources")));
        }
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .inScenario("clone-fail")
                .whenScenarioStateIs("failed-4")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(clusterResourcesWith(vmid, hostnameOf(vmId)))));
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .delete(urlPathEqualTo(qemuPath(vmid)))
                .willReturn(okFixture("70-delete")));
        stubTaskStatus(DELETE_UPID, "70-delete-status");

        // attempts 1–3: transient 500 → RETRYING with a scheduled backoff run
        for (int attempt = 1; attempt <= 3; attempt++) {
            job.provisionVm(vmId);
            ProvisioningTask task = latestTask(vmId);
            assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.RETRYING);
            assertThat(task.getAttempts()).isEqualTo(attempt);
            assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.CLONE.index());
            assertThat(task.getLastError()).contains("템플릿 복제");
        }
        // attempt 4: budget exhausted → compensation
        job.provisionVm(vmId);

        wm.server().verify(4, postRequestedFor(urlPathEqualTo(qemuPath(9000) + "/clone")));
        wm.server().verify(1, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));
        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.FAILED);
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.ERROR);
        assertThat(vm.getStatusDetail()).startsWith("생성 실패");
        assertThat(vm.getProxmoxVmid()).isNull();
        assertThat(jdbc.queryForObject("select status from ip_allocations where vm_id = ?",
                String.class, vmId)).isEqualTo("RELEASED");
    }

    // ── ② start times out → retries exhausted → NEEDS_ADMIN, VM intact ───────

    @Test
    void startTimeoutParksNeedsAdminWithoutDestroying() {
        long vmId = createVm();
        int vmid = 112;
        stubNextId(vmid);
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));
        stubClone();
        stubConfig(vmid);
        stubResize(vmid);
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(vmid) + "/status/start"))
                .willReturn(okFixture("40-start")));
        // the start task never stops → awaitTask hits task-poll-timeout (1 s)
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(START_UPID)))
                .willReturn(okFixture("10-clone-status-running")));

        for (int attempt = 1; attempt <= 4; attempt++) {
            job.provisionVm(vmId);
        }

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.NEEDS_ADMIN);
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.START.index());
        assertThat(task.getLastError()).contains("VM 시작");
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
        // the VM is never destroyed: vmid kept, IP kept, no DELETE call
        assertThat(vm.getProxmoxVmid()).isEqualTo(vmid);
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));
        assertThat(jdbc.queryForObject("select status from ip_allocations where vm_id = ?",
                String.class, vmId)).isEqualTo("ALLOCATED");
        // start was retried on every run (steps 0–6 were not re-executed)
        wm.server().verify(4, postRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/status/start")));
        wm.server().verify(1, postRequestedFor(urlPathEqualTo(qemuPath(9000) + "/clone")));
    }

    // ── ③ crash mid-clone → resume clones exactly once and completes ─────────

    @Test
    void interruptedCloneResumesWithoutDuplicateClone() {
        long vmId = createVm();
        int vmid = 113;
        String ip = preallocateIp(vmId);
        stubNextId(vmid);
        // Run 1: clone starts, but polling its task fails (worker "crash") —
        // the VM materializes on Proxmox anyway, like a real interrupted clone.
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .inScenario("crash").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okFixture("03-cluster-resources")));
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(9000) + "/clone"))
                .inScenario("crash").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okFixture("10-clone"))
                .willSetStateTo("vm-exists"));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(CLONE_UPID)))
                .inScenario("crash").whenScenarioStateIs("vm-exists")
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":null,\"message\":\"connection interrupted\\n\"}")));
        // Run 2: the VMID now exists → clone must be skipped
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .inScenario("crash").whenScenarioStateIs("vm-exists")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(clusterResourcesWith(vmid, hostnameOf(vmId)))));
        stubConfig(vmid);
        stubResize(vmid);
        stubStart(vmid);
        stubAgent(vmid, ip);

        job.provisionVm(vmId);
        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.RETRYING);
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.CLONE.index());

        job.provisionVm(vmId); // the scheduled retry run

        wm.server().verify(1, postRequestedFor(urlPathEqualTo(qemuPath(9000) + "/clone")));
        task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.FINALIZE.index());
        assertThat(task.getLastError()).isNull();
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(vm.getStatusDetail()).isEqualTo(ProvisionVmJob.COMPLETED_DETAIL);
        assertThat(vm.getProxmoxVmid()).isEqualTo(vmid);

        // cloud-init config carried the generated credentials and network
        assertThat(vm.getInitialPassword()).isNotNull().hasSize(24);
        assertThat(passwordEncoder.matches(vm.getInitialPassword(),
                vm.getInitialPasswordHash())).isTrue();
        String expectedIpconfig = URLEncoder.encode("ip=" + ip + "/16,gw=172.29.0.1",
                StandardCharsets.UTF_8);
        wm.server().verify(putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config"))
                .withRequestBody(containing("cipassword="))
                .withRequestBody(containing("ciuser=student"))
                .withRequestBody(containing("ipconfig0=" + expectedIpconfig))
                .withRequestBody(containing("onboot=1"))
                .withRequestBody(containing("tags=pickle")));
        assertThat(jdbc.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = 'CREATE'",
                Long.class, vmId)).isEqualTo(1);
    }

    // ── ④ duplicate provisionVm calls are guarded no-ops ─────────────────────

    @Test
    void duplicateCallsNoOpOnFinishedAndRunningTasks() {
        // duplicate approve after the task finished → nothing happens
        long doneVmId = createVm();
        jdbc.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 9, 'DONE', 1)
                """, doneVmId);
        job.provisionVm(doneVmId);
        assertThat(latestTask(doneVmId).getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);

        // concurrent duplicate: another worker owns the RUNNING task → no-op
        long runningVmId = createVm();
        jdbc.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 4, 'RUNNING', 1)
                """, runningVmId);
        job.provisionVm(runningVmId);
        ProvisioningTask task = latestTask(runningVmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.RUNNING);
        assertThat(task.getCurrentStep()).isEqualTo(4);

        // neither call touched Proxmox or the VMs
        assertThat(wm.server().getAllServeEvents()).isEmpty();
        assertThat(vmRepository.findById(doneVmId).orElseThrow().getStatus())
                .isEqualTo(VmStatus.CREATING);
        assertThat(vmRepository.findById(runningVmId).orElseThrow().getStatus())
                .isEqualTo(VmStatus.CREATING);
    }

    // ── ⑤ resume at step 8 completes without ever touching clone/config ──────

    @Test
    void resumeFromVerifyStepCompletesWithoutCloneOrConfig() {
        long vmId = createVm();
        int vmid = 115;
        String ip = preallocateIp(vmId);
        jdbc.update("update vms set proxmox_vmid = ? where id = ?", vmid, vmId);
        jdbc.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 8, 'PENDING', 0)
                """, vmId);
        jdbc.update("insert into group_members (group_id, user_id, role) values (?, ?, 'OWNER')",
                groupId, adminUserId);
        stubAgent(vmid, ip);

        job.provisionVm(vmId);

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath(9000) + "/clone")));
        wm.server().verify(0, putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config")));
        assertThat(jdbc.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = 'CREATE'",
                Long.class, vmId)).isEqualTo(1);

        // the completion notification is emailed by the dispatcher to the
        // group OWNER with the no-backup notice
        notificationDispatchJob.dispatch();
        MailMessage mail = mockMailSender.lastMessageTo("orgadmin@pickle.local");
        assertThat(mail).isNotNull();
        assertThat(mail.subject()).contains(vm.getHostname());
        assertThat(mail.body())
                .contains(ip)
                .contains("플랫폼은 VM 데이터를 백업하지 않습니다");
    }

    // ── ⑥ force delete mid-pipeline → resumed run halts before any call ──

    @Test
    void pipelineHaltsWhenVmLeavesCreatingMidFlight() {
        long vmId = createVm();
        // a RETRYING task parked at the clone step, waiting for its backoff run
        jdbc.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 4, 'RETRYING', 1)
                """, vmId);
        // a force delete flips the VM to DELETING during the backoff
        jdbc.update("update vms set status = 'DELETING' where id = ?", vmId);

        job.provisionVm(vmId);

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.FAILED);
        assertThat(task.getLastError()).contains("DELETING").contains("중단");
        // clone/start were never called — the guest is not resurrected
        assertThat(wm.server().getAllServeEvents()).isEmpty();
        assertThat(vmRepository.findById(vmId).orElseThrow().getStatus())
                .isEqualTo(VmStatus.DELETING);
    }

    // ── ⑦ compensation must not destroy a foreign guest at our vmid ──────────

    @Test
    void compensationParksInsteadOfDestroyingForeignGuest() {
        long vmId = createVm();
        int vmid = 118;
        stubNextId(vmid);
        // clone fails permanently 4 times, then compensation looks the vmid up —
        // and finds a guest that is not ours (foreign name, no pickle tag)
        for (int attempt = 0; attempt < 4; attempt++) {
            String from = attempt == 0 ? Scenario.STARTED : "failed-" + attempt;
            wm.server().stubFor(post(urlPathEqualTo(qemuPath(9000) + "/clone"))
                    .inScenario("clone-fail-foreign")
                    .whenScenarioStateIs(from)
                    .willReturn(jsonFixture(500, "11-clone-dup-error"))
                    .willSetStateTo("failed-" + (attempt + 1)));
            wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                    .inScenario("clone-fail-foreign")
                    .whenScenarioStateIs(from)
                    .willReturn(okFixture("03-cluster-resources")));
        }
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .inScenario("clone-fail-foreign")
                .whenScenarioStateIs("failed-4")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(clusterResourcesWith(vmid, "somebody-elses-vm"))));

        for (int attempt = 1; attempt <= 4; attempt++) {
            job.provisionVm(vmId);
        }

        // destroy was never called; the task parked for an operator instead
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));
        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.NEEDS_ADMIN);
        assertThat(task.getLastError()).contains("보상 실패");
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
    }

    // ── ⑧ Proxmox nextid re-issues a destroyed VM's vmid → provisioning works ─

    @Test
    void recycledVmidOfDeletedVmCanBeReassigned() {
        // a destroyed VM keeps its vmid on the DELETED row (V9 partial unique)
        int vmid = 116;
        long deletedVmId = createVm();
        jdbc.update("""
                update vms set proxmox_vmid = ?, status = 'DELETED', deleted_at = now()
                 where id = ?
                """, vmid, deletedVmId);

        long vmId = createVm();
        String ip = preallocateIp(vmId);
        // Proxmox re-issued the destroyed guest's vmid
        stubNextId(vmid);
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));
        stubClone();
        stubConfig(vmid);
        stubResize(vmid);
        stubStart(vmid);
        stubAgent(vmid, ip);

        job.provisionVm(vmId);

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(vm.getProxmoxVmid()).isEqualTo(vmid);
        // the DELETED row still carries the vmid for the audit trail
        assertThat(jdbc.queryForObject("select proxmox_vmid from vms where id = ?",
                Integer.class, deletedVmId)).isEqualTo(vmid);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal request→vm graph, mirroring what an approval writes. */
    private long createVm() {
        long requestId = jdbc.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '파이프라인 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, adminUserId, templateId);
        String hostname = "pipe-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbc.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }

    /** Allocates the VM's IP up front so stubs can carry the exact address. */
    private String preallocateIp(long vmId) {
        var allocation = ipamService.allocate(poolId, vmId);
        jdbc.update("update vms set ip_allocation_id = ? where id = ?", allocation.getId(), vmId);
        String ip = allocation.getIp();
        int slash = ip.indexOf('/');
        return slash >= 0 ? ip.substring(0, slash) : ip;
    }

    private ProvisioningTask latestTask(long vmId) {
        return taskRepository.findByVmIdOrderByIdDesc(vmId).getFirst();
    }

    private void stubNextId(int vmid) {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/nextid"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":\"" + vmid + "\"}")));
    }

    private void stubClone() {
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(9000) + "/clone"))
                .willReturn(okFixture("10-clone")));
        stubTaskStatus(CLONE_UPID, "10-clone-status");
    }

    private void stubConfig(int vmid) {
        wm.server().stubFor(put(urlPathEqualTo(qemuPath(vmid) + "/config"))
                .willReturn(okFixture("20-config")));
    }

    private void stubResize(int vmid) {
        wm.server().stubFor(put(urlPathEqualTo(qemuPath(vmid) + "/resize"))
                .willReturn(okFixture("30-resize")));
        stubTaskStatus(RESIZE_UPID, "30-resize-status");
    }

    private void stubStart(int vmid) {
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(vmid) + "/status/start"))
                .willReturn(okFixture("40-start")));
        stubTaskStatus(START_UPID, "40-start-status");
    }

    /** Agent ping answers immediately; netif reports the allocated IP. */
    private void stubAgent(int vmid, String ip) {
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(vmid) + "/agent/ping"))
                .willReturn(okFixture("50-agent-ping")));
        wm.server().stubFor(get(urlPathEqualTo(qemuPath(vmid) + "/agent/network-get-interfaces"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("51-agent-netif").replace("172.29.255.250", ip))));
    }

    private void stubTaskStatus(String upid, String fixtureName) {
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(upid)))
                .willReturn(okFixture(fixtureName)));
    }

    private static String qemuPath(int vmid) {
        return "/api2/json/nodes/" + NODE + "/qemu/" + vmid;
    }

    private static String taskStatusPath(String upid) {
        return "/api2/json/nodes/" + NODE + "/tasks/" + upid + "/status";
    }

    /** cluster/resources body where the given VMID exists (post-clone state). */
    private static String clusterResourcesWith(int vmid, String name) {
        return "{\"data\":[{\"vmid\":" + vmid + ",\"type\":\"qemu\",\"node\":\"" + NODE
                + "\",\"status\":\"stopped\",\"name\":\"" + name + "\",\"maxcpu\":1,"
                + "\"maxmem\":1073741824,\"template\":0}]}";
    }

    private String hostnameOf(long vmId) {
        return vmRepository.findById(vmId).orElseThrow().getHostname();
    }
}
