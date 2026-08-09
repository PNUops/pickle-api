package kr.ac.pusan.pickle.provisioning;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * Fault-injection tests of the provision pipeline
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
            "UPID:pve1:0006D77B:00548A83:6A4E2CB0:qmclone:1000:pickle@pve!pickle-api:";
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
    private kr.ac.pusan.pickle.common.crypto.CredentialCipher credentialCipher;

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long nodeId;
    private long poolId;
    private long imageId;
    private long adminUserId;
    private long workspaceId;

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
        orgId = SeedFixtures.seedOrgId(jdbc);
        nodeId = jdbc.queryForObject("select id from nodes where name = ?", Long.class, NODE);
        poolId = jdbc.queryForObject("select ip_pool_id from nodes where id = ?", Long.class, nodeId);
        imageId = jdbc.queryForObject("select min(id) from os_images", Long.class);
        adminUserId = SeedFixtures.orgadminId(jdbc);
        String slug = "pipe-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbc.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    // ── ① clone keeps failing → retries exhausted → full compensation ────────

    @Test
    void cloneFailureExhaustsRetriesAndCompensates() {
        long vmId = createVm();
        int vmid = 111;
        preassignVmid(vmId, vmid);
        // Scenario: 4 failing clone calls; after the 4th, the half-created VM
        // "exists" so the compensation existence check finds and destroys it.
        for (int attempt = 0; attempt < 4; attempt++) {
            String from = attempt == 0 ? Scenario.STARTED : "failed-" + attempt;
            wm.server().stubFor(post(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone"))
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
        // compensation clears the always-on protection flag before the destroy
        stubConfig(vmid);

        // attempts 1–3: transient 500 → RETRYING with a scheduled backoff run
        for (int attempt = 1; attempt <= 3; attempt++) {
            job.provisionVm(vmId);
            ProvisioningTask task = latestTask(vmId);
            assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.RETRYING);
            assertThat(task.getAttempts()).isEqualTo(attempt);
            assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.CLONE.index());
            assertThat(task.getLastError()).contains("OS 이미지 복제");
        }
        // attempt 4: budget exhausted → compensation
        job.provisionVm(vmId);

        wm.server().verify(4, postRequestedFor(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone")));
        wm.server().verify(putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config"))
                .withRequestBody(containing("protection=0")));
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
        preassignVmid(vmId, vmid);
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
        wm.server().verify(1, postRequestedFor(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone")));
    }

    // ── ②b host-key collection keeps failing → NEEDS_ADMIN, VM intact ────────

    @Test
    void hostKeyReadFailureParksNeedsAdminWithoutDestroying() {
        long vmId = createVm();
        int vmid = 117;
        String ip = preallocateIp(vmId);
        preassignVmid(vmId, vmid);
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));
        stubClone();
        stubConfig(vmid);
        stubResize(vmid);
        stubStart(vmid);
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(vmid) + "/agent/ping"))
                .willReturn(okFixture("50-agent-ping")));
        wm.server().stubFor(get(urlPathEqualTo(qemuPath(vmid) + "/agent/network-get-interfaces"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("51-agent-netif").replace("172.29.255.250", ip))));
        // the guest agent never yields the host key file (still regenerating)
        wm.server().stubFor(get(urlPathEqualTo(qemuPath(vmid) + "/agent/file-read"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":null,\"message\":\"No such file or directory\"}")));

        for (int attempt = 1; attempt <= 4; attempt++) {
            job.provisionVm(vmId);
        }

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.NEEDS_ADMIN);
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.HOSTKEY.index());
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
        assertThat(vm.getSshHostKey()).isNull();
        // never destroyed
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));
    }

    // ── ③ crash mid-clone → resume clones exactly once and completes ─────────

    @Test
    void interruptedCloneResumesWithoutDuplicateClone() {
        long vmId = createVm();
        int vmid = 113;
        String ip = preallocateIp(vmId);
        preassignVmid(vmId, vmid);
        // Run 1: clone starts, but polling its task fails (worker "crash") —
        // the VM materializes on Proxmox anyway, like a real interrupted clone.
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .inScenario("crash").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okFixture("03-cluster-resources")));
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone"))
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

        wm.server().verify(1, postRequestedFor(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone")));
        task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.FINALIZE.index());
        assertThat(task.getLastError()).isNull();
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(vm.getStatusDetail()).isEqualTo(ProvisionVmJob.COMPLETED_DETAIL);
        assertThat(vm.getProxmoxVmid()).isEqualTo(vmid);

        // cloud-init config carried the generated credentials and network;
        // the DB keeps ciphertext + hash, never plaintext
        assertThat(vm.getPasswordEnc()).isNotNull().startsWith("v1:");
        String plaintext = credentialCipher.decrypt(vm.getPasswordEnc());
        assertThat(plaintext).hasSize(24);
        assertThat(passwordEncoder.matches(plaintext, vm.getPasswordHash())).isTrue();
        String expectedIpconfig = URLEncoder.encode("ip=" + ip + "/16,gw=172.29.0.1",
                StandardCharsets.UTF_8);
        // The gateway platform key is pre-encoded once by configure() (with
        // space→%20 so PVE's urlencoded validator, which rejects space AND '+',
        // passes) and then a second time by the form encoder. Verify the exact
        // wire value AND that PVE's decode round-trip restores the raw key:
        //   wire (space=%2520) --form decode--> once-encoded (space=%20)
        //        --cloud-init uri_unescape--> original key.
        String platformKey = "ssh-ed25519 "
                + "AAAAC3NzaC1lZDI1NTE5AAAAIPlatformGatewayKeyFixtureForTests pickle-sshgw";
        String innerEncoded = URLEncoder.encode(platformKey, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String sshkeysWire = URLEncoder.encode(innerEncoded, StandardCharsets.UTF_8);
        // the bug left a literal '+' from the space; the fix makes it survive as %2520
        assertThat(sshkeysWire).contains("%2520").doesNotContain("+");
        assertThat(URLDecoder.decode(URLDecoder.decode(sshkeysWire, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8)).isEqualTo(platformKey);
        wm.server().verify(putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config"))
                .withRequestBody(containing("cipassword="))
                // the VM row's own account, not a platform-wide constant
                .withRequestBody(containing("ciuser=rocky"))
                .withRequestBody(containing("ipconfig0=" + expectedIpconfig))
                .withRequestBody(containing("sshkeys=" + sshkeysWire))
                .withRequestBody(containing("onboot=1"))
                .withRequestBody(containing("protection=1"))
                .withRequestBody(containing("tags=pickle")));
        // HOSTKEY step collected ALL host-key types, normalized (comment dropped),
        // newline-joined in the order read (ed25519, ecdsa, rsa)
        assertThat(vm.getSshHostKey()).isEqualTo(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGuestEd25519ForPipeline\n"
                        + "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItEcdsaForPipeline\n"
                        + "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABRsaForPipeline");
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
        jdbc.update("insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                workspaceId, adminUserId);
        stubAgent(vmid, ip);

        job.provisionVm(vmId);

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone")));
        wm.server().verify(0, putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config")));
        assertThat(jdbc.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = 'CREATE'",
                Long.class, vmId)).isEqualTo(1);

        // the completion notification is emailed by the dispatcher with the
        // no-backup notice; the audience is the VM's owner on its access list
        // plus the owners of the owning workspace, which here are the same account
        notificationDispatchJob.dispatch();
        MailMessage mail = mockMailSender.lastMessageTo(SeedFixtures.ORGADMIN_EMAIL);
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
        preassignVmid(vmId, vmid);
        // clone fails permanently 4 times, then compensation looks the vmid up —
        // and finds a guest that is not ours (foreign name, no pickle tag)
        for (int attempt = 0; attempt < 4; attempt++) {
            String from = attempt == 0 ? Scenario.STARTED : "failed-" + attempt;
            wm.server().stubFor(post(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone"))
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

        // destroy was never called — and neither was the protection clear,
        // which sits behind the same identity guard; the task parked instead
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));
        wm.server().verify(0, putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config")));
        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.NEEDS_ADMIN);
        assertThat(task.getLastError()).contains("보상 실패");
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
    }

    // ── ⑧ a deleted row's vmid may be re-assigned manually (V9 partial unique) ─

    @Test
    void recycledVmidOfDeletedVmCanBeReassigned() {
        // The V50 sequence never re-issues a vmid, but the schema deliberately
        // only guards active rows (V9): a manually re-assigned number that a
        // DELETED row still carries must provision fine.
        // a destroyed VM keeps its vmid on the DELETED row (V9 partial unique)
        int vmid = 116;
        long deletedVmId = createVm();
        jdbc.update("""
                update vms set proxmox_vmid = ?, status = 'DELETED', deleted_at = now()
                 where id = ?
                """, vmid, deletedVmId);

        long vmId = createVm();
        String ip = preallocateIp(vmId);
        preassignVmid(vmId, vmid);
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

    // ── ⑩ vmid occupied by a resident guest → park, touch nothing ────────────

    @Test
    void vmidConflictParksWithoutTouchingResidentGuest() {
        long vmId = createVm();
        String ip = preallocateIp(vmId);
        int vmid = 119;
        preassignVmid(vmId, vmid);
        // resident guest at our number with a foreign name — even pickle-tagged
        // (the DB-restore orphan case): must not be skipped over, configured,
        // compensated away or retried
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(clusterResourcesWith(vmid, "restore-orphan", "dev;pickle"))));

        job.provisionVm(vmId);

        ProvisioningTask task = latestTask(vmId);
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.NEEDS_ADMIN);
        assertThat(task.getLastError()).contains("vmid_seq");
        // the step regresses to VMID and our claim on the number is dropped
        // (DB only), so force-delete touches nothing and a re-run draws fresh
        assertThat(task.getCurrentStep()).isEqualTo(ProvisioningStep.VMID.index());
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.NEEDS_ADMIN);
        assertThat(vm.getProxmoxVmid()).isNull();
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone")));
        wm.server().verify(0, putRequestedFor(urlPathEqualTo(qemuPath(vmid) + "/config")));
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuPath(vmid))));

        // recovery: the operator resyncs vmid_seq (a no-op here — the test
        // sequence is already past the resident) and re-runs via the admin
        // path (NEEDS_ADMIN → RETRYING, attempts reset — requeueForAdminRetry
        // semantics), which must draw a FRESH number and complete
        jdbc.update("update provisioning_tasks set status = 'RETRYING', attempts = 0,"
                + " last_error = null where id = ?", task.getId());
        jdbc.update("update vms set status = 'CREATING', status_detail = null where id = ?", vmId);
        int fresh = jdbc.queryForObject("select nextval('vmid_seq')", Integer.class) + 1;
        stubClone();
        stubConfig(fresh);
        stubResize(fresh);
        stubStart(fresh);
        stubAgent(fresh, ip);

        job.provisionVm(vmId);

        Vm recovered = vmRepository.findById(vmId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(recovered.getProxmoxVmid()).isEqualTo(fresh);
        assertThat(latestTask(vmId).getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
    }

    // ── ⑨ vmid comes from the DB sequence (user-VM band, monotonic) ──────────

    @Test
    void assignsVmidFromTheSequenceBand() {
        long vmId = createVm();
        String ip = preallocateIp(vmId);
        int vmid = jdbc.queryForObject("select nextval('vmid_seq')", Integer.class) + 1;
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));
        stubClone();
        stubConfig(vmid);
        stubResize(vmid);
        stubStart(vmid);
        stubAgent(vmid, ip);

        job.provisionVm(vmId);

        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(vm.getProxmoxVmid()).isEqualTo(vmid).isGreaterThanOrEqualTo(100_000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal request→vm graph, mirroring what an approval writes. */
    private long createVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, adminUserId, "파이프라인 테스트", imageId, 1, 1024, 10);
        String hostname = "pipe-vm-" + UUID.randomUUID().toString().substring(0, 12);
        // The account is deliberately not 'ubuntu': approval copies it off the OS
        // image, so the pipeline has to carry this row's value into cloud-init
        // rather than fall back on the account the platform started with.
        long vmId = jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, ssh_username, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 'rocky', 1, 1024, 10)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname, imageId);
        // Approval also opens the VM's access list with its requester on it, and
        // that list is where the pipeline's notices look for an audience — a
        // fixture VM without it is one nobody is responsible for.
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, adminUserId, "OWNER");
        return vmId;
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

    /** Pre-assigns the vmid, exercising the crash-guard path in assignVmid. */
    private void preassignVmid(long vmId, int vmid) {
        jdbc.update("update vms set proxmox_vmid = ? where id = ?", vmid, vmId);
    }

    private void stubClone() {
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(SeedFixtures.TEMPLATE_VMID) + "/clone"))
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

    /** Agent ping answers immediately; netif reports the allocated IP; the
     *  HOSTKEY step reads the guest's host public key. */
    private void stubAgent(int vmid, String ip) {
        wm.server().stubFor(post(urlPathEqualTo(qemuPath(vmid) + "/agent/ping"))
                .willReturn(okFixture("50-agent-ping")));
        wm.server().stubFor(get(urlPathEqualTo(qemuPath(vmid) + "/agent/network-get-interfaces"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("51-agent-netif").replace("172.29.255.250", ip))));
        // HOSTKEY step reads every host-key type the VM presents
        stubHostKeyFile(vmid, "/etc/ssh/ssh_host_ed25519_key.pub",
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGuestEd25519ForPipeline root@vm");
        stubHostKeyFile(vmid, "/etc/ssh/ssh_host_ecdsa_key.pub",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItEcdsaForPipeline root@vm");
        stubHostKeyFile(vmid, "/etc/ssh/ssh_host_rsa_key.pub",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABRsaForPipeline root@vm");
    }

    private void stubHostKeyFile(int vmid, String path, String line) {
        wm.server().stubFor(get(urlPathEqualTo(qemuPath(vmid) + "/agent/file-read"))
                .withQueryParam("file", equalTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"content\":\"" + line + "\\n\",\"truncated\":false}}")));
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
        return clusterResourcesWith(vmid, name, null);
    }

    private static String clusterResourcesWith(int vmid, String name, String tags) {
        return "{\"data\":[{\"vmid\":" + vmid + ",\"type\":\"qemu\",\"node\":\"" + NODE
                + "\",\"status\":\"stopped\",\"name\":\"" + name + "\",\"maxcpu\":1,"
                + (tags != null ? "\"tags\":\"" + tags + "\"," : "")
                + "\"maxmem\":1073741824,\"template\":0}]}";
    }

    private String hostnameOf(long vmId) {
        return vmRepository.findById(vmId).orElseThrow().getHostname();
    }
}
