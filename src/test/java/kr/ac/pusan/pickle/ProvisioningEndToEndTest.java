package kr.ac.pusan.pickle;

import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.fixture;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.mail.AsyncMailDispatcher;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof of the real provisioning path in one flow: signup → verify
 * (token from the mock mail) → login → TEAM workspace → vm-request → seeded
 * ORG_ADMIN queue → approval context → approve → the JobRunr background server
 * runs the REAL provision pipeline against a WireMock Proxmox (pve1 captures,
 * happy path) → /vms shows RUNNING with the allocated IP, one-shot password
 * availability and provisioning DONE → vm_events CREATE + owner mail → audit
 * trail exists.
 *
 * <p>The background job server is enabled just for this test (it stays off in
 * application-test.yml), so the enqueued job is genuinely picked up from the
 * jobrunr tables and executed by a worker thread — the test fails if the VM
 * never reaches RUNNING.</p>
 */
@SpringBootTest(properties = {
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "pickle.proxmox.token-id=pickle@pve!pickle-api",
        "pickle.proxmox.token-secret=wiremock-test-secret",
        "pickle.proxmox.task-poll-interval=100ms",
        "pickle.proxmox.task-poll-timeout=10s"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ProvisioningEndToEndTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private static final String USER_EMAIL = "e2e.user@pusan.ac.kr";
    private static final String USER_PASSWORD = "E2e-Corr3ct-horse!";

    /**
     * First value of {@code vmid_seq} (V50) — deterministic because this test
     * class boots its own embedded PostgreSQL and provisions exactly one VM.
     */
    private static final int VMID = 100000;

    /**
     * First allocatable address of the seeded guest-private pool
     * (172.29.0.0/16 minus the two reserved /24s) — deterministic because this
     * test class boots its own embedded PostgreSQL and allocates exactly once.
     */
    private static final String EXPECTED_IP = "172.29.1.0";

    private static final String CLONE_UPID =
            "UPID:pve1:0006D77B:00548A83:6A4E2CB0:qmclone:1000:pickle@pve!pickle-api:";
    private static final String RESIZE_UPID =
            "UPID:pve1:0006D8A5:005490E8:6A4E2CC0:resize:102:pickle@pve!pickle-api:";
    private static final String START_UPID =
            "UPID:pve1:0006D8F7:005491B8:6A4E2CC2:qmstart:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private AsyncMailDispatcher mailDispatcher;

    @Autowired
    private kr.ac.pusan.pickle.notification.NotificationDispatchJob notificationDispatchJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

    @Test
    void flowFromSignupToProvisionedVm() throws Exception {
        // 0. the seeded node answers Proxmox calls from WireMock (happy path)
        jdbcTemplate.update("update nodes set api_host = ? where name = 'pve1'", wm.apiHost());
        stubProxmoxHappyPath();

        // 1. signup + email verification (token comes from the mock mail)
        postJson("/api/v1/auth/signup", null,
                Map.of("email", USER_EMAIL, "password", USER_PASSWORD, "name", "종단테스트학생",
                        "consents", java.util.List.of(
                                Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                                Map.of("docType", "PRIVACY_POLICY", "version", 1))))
                .andExpect(status().isAccepted());
        assertThat(mailDispatcher.awaitIdle(Duration.ofSeconds(10)))
                .as("mail dispatcher drained").isTrue();
        MailMessage mail = mockMailSender.lastMessageTo(USER_EMAIL);
        assertThat(mail).as("verification mail recorded by MockMailSender").isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.body());
        assertThat(matcher.find()).isTrue();
        postJson("/api/v1/auth/verify-email", null, Map.of("token", matcher.group(1)))
                .andExpect(status().isOk());

        // 2. login as the user
        String userToken = login(USER_EMAIL, USER_PASSWORD);

        // 3. create a TEAM workspace
        MvcResult workspaceResult = postJson("/api/v1/workspaces", userToken,
                Map.of("kind", "TEAM", "name", "종단 테스트 팀"))
                .andExpect(status().isCreated())
                .andReturn();
        long workspaceId = objectMapper.readTree(workspaceResult.getResponse().getContentAsString())
                .get("id").asLong();

        // 4. reference data: the two request axes from the API; the seed org via
        // JDBC because it is hidden and USER tokens do not see it in /orgs
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        JsonNode images = getJson("/api/v1/os-images", userToken);
        JsonNode image = findBy(images, "name", "ubuntu-24.04");
        long imageId = image.get("id").asLong();
        JsonNode flavors = getJson("/api/v1/vm-flavors", userToken);
        JsonNode flavor = findBy(flavors, "name", "basic");
        long flavorId = flavor.get("id").asLong();

        // 5. submit the vm-request pre-filled with the chosen preset's specs
        MvcResult requestResult = postJson("/api/v1/requests", userToken, Map.of(
                "type", "VM",
                "workspaceId", workspaceId,
                "orgId", orgId,
                "purpose", "종단 검증용 서버",
                "vm", Map.of(
                        "imageId", imageId,
                        "flavorId", flavorId,
                        "reqVcpu", flavor.get("vcpu").asInt(),
                        "reqMemoryMb", flavor.get("memoryMb").asInt(),
                        "reqDiskGb", flavor.get("diskGb").asInt())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        long requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString())
                .get("id").asLong();

        // 6. the seeded ORG_ADMIN logs in and finds the request in the queue
        String adminToken = login(SeedFixtures.ORGADMIN_EMAIL, "pickle-test-orgadmin!");
        mockMvc.perform(get("/api/v1/admin/requests?status=SUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestId)).exists());

        // 7. the approval context loads with all panels
        mockMvc.perform(get("/api/v1/admin/requests/" + requestId + "/context")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.workspace.id").value(workspaceId))
                .andExpect(jsonPath("$.orgHeadroom.capacity.cpuThreads").value(40))
                .andExpect(jsonPath("$.guidance").isNotEmpty());

        // 8. approve with the requested spec
        postJson("/api/v1/admin/requests/" + requestId + "/approve", adminToken, Map.of(
                "comment", "요청 사양 그대로 승인합니다.",
                "vm", Map.of(
                        "grantedVcpu", flavor.get("vcpu").asInt(),
                        "grantedMemoryMb", flavor.get("memoryMb").asInt(),
                        "grantedDiskGb", flavor.get("diskGb").asInt(),
                        "grantedImageId", imageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.decision").value("APPROVE"));

        // 9. the background job server runs the real pipeline to completion:
        //    /vms must show RUNNING — the test fails if the VM stays CREATING
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/vms?workspaceId=" + workspaceId)
                                .header("Authorization", "Bearer " + userToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.content[0].status").value("RUNNING")));

        // 10. VM summary/detail carry the granted spec and the pipeline results
        JsonNode vms = getJson("/api/v1/vms", userToken);
        JsonNode vm = vms.get("content").get(0);
        long vmId = vm.get("id").asLong();
        // Generated from the requested display name plus a random suffix.
        assertThat(vm.get("hostname").asString()).matches("[a-z0-9-]+-[a-z0-9]{4}");
        assertThat(vm.get("requestId").asLong()).isEqualTo(requestId);
        assertThat(vm.get("statusDetail").asString()).isEqualTo("프로비저닝 완료");
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.sshUsername").value("ubuntu"))
                .andExpect(jsonPath("$.orgId").value(orgId))
                .andExpect(jsonPath("$.ipAddress").value(EXPECTED_IP))
                .andExpect(jsonPath("$.passwordAvailable").value(true))
                // contract v0.3.1: provisioning surfaces only in-flight or
                // last-failed tasks — a cleanly finished pipeline shows null
                .andExpect(jsonPath("$.provisioning").value(org.hamcrest.Matchers.nullValue()));

        // 11. pipeline side effects: vmid/ip persisted, CREATE event, owner mail
        assertThat(jdbcTemplate.queryForObject(
                "select proxmox_vmid from vms where id = ?", Integer.class, vmId)).isEqualTo(VMID);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from vm_events where vm_id = ? and type = 'CREATE'",
                Long.class, vmId)).isEqualTo(1);
        // creation notice is a notifications row; the dispatcher emails it
        // (running it directly — the 1-minute recurring schedule is too slow
        // for the test, and the CAS claim makes the direct call race-safe)
        notificationDispatchJob.dispatch();
        MailMessage createdMail = mockMailSender.lastMessageTo(USER_EMAIL);
        assertThat(createdMail.subject()).contains(vm.get("hostname").asString());
        assertThat(createdMail.body())
                .contains(EXPECTED_IP)
                .contains("플랫폼은 VM 데이터를 백업하지 않습니다");

        // 12. the audit trail covers the whole flow
        for (String action : new String[] {"auth.signup", "auth.verify", "auth.login",
                "workspace.create", "request.create", "request.approve"}) {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from audit_logs where action = ?", Long.class, action);
            assertThat(count).as("audit rows for %s", action).isPositive();
        }
    }

    /** Happy-path Proxmox stubs from the captured pve1 responses. */
    private void stubProxmoxHappyPath() {
        String qemu = "/api2/json/nodes/pve1/qemu/" + VMID;
        // no VMID 100000 in the capture → the clone-exists guard lets the clone run
        wm.server().stubFor(WireMock.get(WireMock.urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(okFixture("03-cluster-resources")));
        wm.server().stubFor(WireMock.post(WireMock.urlPathEqualTo("/api2/json/nodes/pve1/qemu/" + SeedFixtures.TEMPLATE_VMID + "/clone"))
                .willReturn(okFixture("10-clone")));
        stubTaskStatus(CLONE_UPID, "10-clone-status");
        wm.server().stubFor(WireMock.put(WireMock.urlPathEqualTo(qemu + "/config"))
                .willReturn(okFixture("20-config")));
        wm.server().stubFor(WireMock.put(WireMock.urlPathEqualTo(qemu + "/resize"))
                .willReturn(okFixture("30-resize")));
        stubTaskStatus(RESIZE_UPID, "30-resize-status");
        wm.server().stubFor(WireMock.post(WireMock.urlPathEqualTo(qemu + "/status/start"))
                .willReturn(okFixture("40-start")));
        stubTaskStatus(START_UPID, "40-start-status");
        wm.server().stubFor(WireMock.post(WireMock.urlPathEqualTo(qemu + "/agent/ping"))
                .willReturn(okFixture("50-agent-ping")));
        wm.server().stubFor(WireMock.get(WireMock.urlPathEqualTo(qemu + "/agent/network-get-interfaces"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("51-agent-netif")
                                .replace("172.29.255.250", EXPECTED_IP))));
        // HOSTKEY step reads the guest's SSH host public key
        wm.server().stubFor(WireMock.get(WireMock.urlPathEqualTo(qemu + "/agent/file-read"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"content\":\"ssh-ed25519 "
                                + "AAAAC3NzaC1lZDI1NTE5AAAAIGuestHostKeyFixtureForE2E root@vm\\n\","
                                + "\"truncated\":false}}")));
    }

    private static void stubTaskStatus(String upid, String fixtureName) {
        wm.server().stubFor(WireMock.get(WireMock.urlPathEqualTo(
                "/api2/json/nodes/pve1/tasks/" + upid + "/status"))
                .willReturn(okFixture(fixtureName)));
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = postJson("/api/v1/auth/login", null, Map.of("email", email, "password", password))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    private JsonNode getJson(String uri, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(uri).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asString())) {
                return node;
            }
        }
        throw new AssertionError("No element with " + field + "=" + value + " in " + array);
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        var request = post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }
}
