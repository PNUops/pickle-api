package kr.ac.pusan.pickle.proxmox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.jsonFixture;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.proxmox.dto.AgentInterface;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.proxmox.dto.NodeStatusInfo;
import kr.ac.pusan.pickle.proxmox.dto.TaskStatus;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ProxmoxClient against WireMock serving responses captured from the real
 * pve1 (PVE 9.2.3, 2026-07-08). No Spring context: the client only needs its
 * properties record, and the api host is a per-call argument.
 */
class ProxmoxClientTest {

    private static final String NODE = "pve1";
    private static final String TOKEN_ID = "pickle@pve!pickle-api";
    private static final String TOKEN_SECRET = "wiremock-test-secret";
    private static final String EXPECTED_AUTHORIZATION = "PVEAPIToken=" + TOKEN_ID + "=" + TOKEN_SECRET;

    /** UPIDs exactly as returned in the captured fixtures. */
    private static final String CLONE_UPID =
            "UPID:pve1:0006D77B:00548A83:6A4E2CB0:qmclone:9000:pickle@pve!pickle-api:";
    private static final String SHUTDOWN_UPID =
            "UPID:pve1:0006DC5F:0054AA57:6A4E2D01:qmshutdown:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    private ProxmoxClient client;

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
        client = new ProxmoxClient(new ProxmoxProperties(
                TOKEN_ID, TOKEN_SECRET, null,
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(5)));
    }

    private static String taskStatusPath(String upid) {
        return "/api2/json/nodes/" + NODE + "/tasks/" + upid + "/status";
    }

    @Test
    void nextIdParsesStringDataAndSendsTokenHeader() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okFixture("02-nextid")));

        assertThat(client.nextId(wm.apiHost())).isEqualTo(102);

        wm.server().verify(getRequestedFor(urlPathEqualTo("/api2/json/cluster/nextid"))
                .withHeader("Authorization", equalTo(EXPECTED_AUTHORIZATION)));
    }

    @Test
    void cloneFlowReturnsUpidAndAwaitTaskPollsUntilStopped() {
        wm.server().stubFor(post(urlPathEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(okFixture("10-clone")));
        // Scenario: first poll sees the task still running, second sees it done.
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(CLONE_UPID)))
                .inScenario("clone-task")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okFixture("10-clone-status-running"))
                .willSetStateTo("first-poll-done"));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(CLONE_UPID)))
                .inScenario("clone-task")
                .whenScenarioStateIs("first-poll-done")
                .willReturn(okFixture("10-clone-status")));

        String upid = client.clone(wm.apiHost(), NODE, 9000, 102, "test-vm-1");
        assertThat(upid).isEqualTo(CLONE_UPID);

        TaskStatus done = client.awaitTask(wm.apiHost(), NODE, upid);
        assertThat(done.isOk()).isTrue();
        assertThat(done.exitstatus()).isEqualTo("OK");

        wm.server().verify(postRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("newid=102"))
                .withRequestBody(containing("name=test-vm-1"))
                .withRequestBody(containing("full=1")));
        wm.server().verify(2, getRequestedFor(urlPathEqualTo(taskStatusPath(CLONE_UPID))));
    }

    @Test
    void duplicateVmidClonePreservesPveErrorMessage() {
        wm.server().stubFor(post(urlPathEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(jsonFixture(500, "11-clone-dup-error")));

        ProxmoxApiException e = catchThrowableOfType(ProxmoxApiException.class,
                () -> client.clone(wm.apiHost(), NODE, 9000, 102, "test-vm-1"));

        assertThat(e.statusCode()).isEqualTo(500);
        assertThat(e.apiMessage()).contains("unable to create VM 102: config file already exists");
        assertThat(e.getMessage()).contains("config file already exists");
        // 5xx counts as transient per the retry-classification contract; the
        // provisioning steps stay idempotent to make retrying this safe.
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void nonOkExitstatusBecomesTaskFailedException() {
        // Captured real failure: guest ignored ACPI shutdown until the timeout.
        wm.server().stubFor(post(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/status/shutdown"))
                .willReturn(okFixture("61-shutdown")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(SHUTDOWN_UPID)))
                .willReturn(okFixture("61-shutdown-status")));

        String upid = client.shutdown(wm.apiHost(), NODE, 102, 10);
        assertThat(upid).isEqualTo(SHUTDOWN_UPID);
        wm.server().verify(postRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/status/shutdown"))
                .withRequestBody(containing("timeout=10")));

        ProxmoxTaskFailedException e = catchThrowableOfType(ProxmoxTaskFailedException.class,
                () -> client.awaitTask(wm.apiHost(), NODE, upid));
        assertThat(e.exitstatus()).isEqualTo("VM quit/powerdown failed - got timeout");
        assertThat(e.upid()).isEqualTo(SHUTDOWN_UPID);
    }

    @Test
    void awaitTaskTimesOutWhileTaskKeepsRunning() {
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(CLONE_UPID)))
                .willReturn(okFixture("10-clone-status-running")));

        assertThatThrownBy(() -> client.awaitTask(wm.apiHost(), NODE, CLONE_UPID, Duration.ofMillis(300)))
                .isInstanceOf(ProxmoxTimeoutException.class)
                .hasMessageContaining(CLONE_UPID);

        // Polled more than once before giving up (50ms base interval, ±20%).
        assertThat(wm.server().findAll(getRequestedFor(urlPathEqualTo(taskStatusPath(CLONE_UPID))))
                .size()).isGreaterThan(2);
    }

    @Test
    void agentNetworkInterfacesParsesKebabCaseFields() {
        wm.server().stubFor(get(urlPathEqualTo(
                "/api2/json/nodes/pve1/qemu/102/agent/network-get-interfaces"))
                .willReturn(okFixture("51-agent-netif")));

        List<AgentInterface> interfaces = client.agentNetworkInterfaces(wm.apiHost(), NODE, 102);

        assertThat(interfaces).hasSize(2);
        AgentInterface eth0 = interfaces.stream()
                .filter(i -> "eth0".equals(i.name()))
                .findFirst().orElseThrow();
        assertThat(eth0.ipAddresses())
                .anySatisfy(ip -> {
                    assertThat(ip.ipAddress()).isEqualTo("172.29.255.250");
                    assertThat(ip.type()).isEqualTo("ipv4");
                    assertThat(ip.prefix()).isEqualTo(16);
                });
    }

    @Test
    void agentPingReflectsHttpOutcome() {
        wm.server().stubFor(post(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/agent/ping"))
                .willReturn(okFixture("50-agent-ping")));
        wm.server().stubFor(post(urlPathEqualTo("/api2/json/nodes/pve1/qemu/103/agent/ping"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null,\"message\":\"QEMU guest agent is not running\\n\"}")));

        assertThat(client.agentPing(wm.apiHost(), NODE, 102)).isTrue();
        assertThat(client.agentPing(wm.apiHost(), NODE, 103)).isFalse();
    }

    @Test
    void agentFileReadReturnsContentAndRejectsTruncated() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/agent/file-read"))
                .withQueryParam("file", equalTo("/etc/ssh/ssh_host_ed25519_key.pub"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":{\"content\":\"ssh-ed25519 AAAAhostkey root@vm\\n\","
                                + "\"truncated\":false}}")));
        assertThat(client.agentFileRead(wm.apiHost(), NODE, 102,
                "/etc/ssh/ssh_host_ed25519_key.pub")).isEqualTo("ssh-ed25519 AAAAhostkey root@vm\n");

        // a truncated read is an error, not a silently-clipped value
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/nodes/pve1/qemu/103/agent/file-read"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":{\"content\":\"partial\",\"truncated\":1}}")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.agentFileRead(wm.apiHost(), NODE, 103, "/big"))
                .isInstanceOf(ProxmoxApiException.class);
    }

    @Test
    void agentSetUserPasswordReflectsHttpOutcome() {
        wm.server().stubFor(post(urlPathEqualTo(
                "/api2/json/nodes/pve1/qemu/102/agent/set-user-password"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null}")));
        wm.server().stubFor(post(urlPathEqualTo(
                "/api2/json/nodes/pve1/qemu/103/agent/set-user-password"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null,\"message\":\"QEMU guest agent is not running\\n\"}")));

        assertThat(client.agentSetUserPassword(wm.apiHost(), NODE, 102, "student", "pw")).isTrue();
        assertThat(client.agentSetUserPassword(wm.apiHost(), NODE, 103, "student", "pw")).isFalse();
        wm.server().verify(postRequestedFor(urlPathEqualTo(
                "/api2/json/nodes/pve1/qemu/102/agent/set-user-password"))
                .withRequestBody(containing("username=student")));
    }

    @Test
    void clusterResourcesParsesInventory() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));

        List<ClusterResource> resources = client.clusterResources(wm.apiHost(), null);

        assertThat(resources).hasSize(3);
        ClusterResource template = resources.stream()
                .filter(r -> Integer.valueOf(9000).equals(r.vmid()))
                .findFirst().orElseThrow();
        assertThat(template.type()).isEqualTo("qemu");
        assertThat(template.name()).isEqualTo("ubuntu-2404-template");
        assertThat(template.node()).isEqualTo(NODE);
        assertThat(template.maxcpu()).isEqualTo(2);
        assertThat(template.maxmem()).isEqualTo(2147483648L);
    }

    @Test
    void clusterResourcesSendsTypeFilter() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));

        client.clusterResources(wm.apiHost(), "vm");

        wm.server().verify(getRequestedFor(urlPathEqualTo("/api2/json/cluster/resources"))
                .withQueryParam("type", equalTo("vm")));
    }

    @Test
    void nodeStatusParsesCapacityFields() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/nodes/pve1/status"))
                .willReturn(okFixture("04-node-status")));

        NodeStatusInfo status = client.nodeStatus(wm.apiHost(), NODE);

        assertThat(status.cpuinfo().cpus()).isEqualTo(40);
        assertThat(status.cpuinfo().sockets()).isEqualTo(2);
        assertThat(status.memory().total()).isEqualTo(84322545664L);
        assertThat(status.memory().available()).isEqualTo(79304998912L);
    }

    @Test
    void resizeSendsFormParamsAndReturnsUpid() {
        wm.server().stubFor(put(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/resize"))
                .willReturn(okFixture("30-resize")));

        String upid = client.resize(wm.apiHost(), NODE, 102, "scsi0", "10G");

        assertThat(upid).startsWith("UPID:pve1:").contains(":resize:102:");
        wm.server().verify(putRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/resize"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("disk=scsi0"))
                .withRequestBody(containing("size=10G")));
    }

    @Test
    void configSendsFormUrlencodedCloudInitParams() {
        wm.server().stubFor(put(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/config"))
                .willReturn(okFixture("20-config")));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("cores", "1");
        params.put("memory", "1024");
        params.put("ciuser", "student");
        params.put("ipconfig0", "ip=172.29.255.250/16,gw=172.29.0.1");
        client.config(wm.apiHost(), NODE, 102, params);

        wm.server().verify(putRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102/config"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("cores=1"))
                .withRequestBody(containing("ciuser=student"))
                // '=' '/' ',' inside values must be form-encoded.
                .withRequestBody(containing(
                        "ipconfig0=ip%3D172.29.255.250%2F16%2Cgw%3D172.29.0.1")));
    }

    @Test
    void deleteSendsPurgeParams() {
        wm.server().stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102"))
                .willReturn(okFixture("70-delete")));

        String upid = client.delete(wm.apiHost(), NODE, 102);

        assertThat(upid).contains(":qmdestroy:102:");
        wm.server().verify(deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102"))
                .withQueryParam("purge", equalTo("1"))
                .withQueryParam("destroy-unreferenced-disks", equalTo("1")));
    }

    @Test
    void missingVmSurfacesPveMessage() {
        // Captured after the VM was destroyed: PVE answers 500, not 404.
        wm.server().stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/102"))
                .willReturn(jsonFixture(500, "71-vm-gone")));

        ProxmoxApiException e = catchThrowableOfType(ProxmoxApiException.class,
                () -> client.delete(wm.apiHost(), NODE, 102));

        assertThat(e.statusCode()).isEqualTo(500);
        assertThat(e.apiMessage()).contains("does not exist");
    }

    @Test
    void transportFailureIsTransientWithoutStatusCode() {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/nextid"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ProxmoxApiException e = catchThrowableOfType(ProxmoxApiException.class,
                () -> client.nextId(wm.apiHost()));

        assertThat(e.statusCode()).isZero();
        assertThat(e.isTransient()).isTrue();
        assertThat(e.getCause()).isNotNull();
    }

    @Test
    void unconfiguredTokenFailsWithClearErrorBeforeAnyRequest() {
        ProxmoxClient unconfigured = new ProxmoxClient(new ProxmoxProperties(
                "", "", null, null, null, null, null));

        assertThatThrownBy(() -> unconfigured.nextId(wm.apiHost()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKLE_PROXMOX_TOKEN_ID");
        // Nothing reached the server: the misconfiguration is caught client-side.
        assertThat(wm.server().getAllServeEvents()).isEmpty();
    }
}
