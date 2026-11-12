package kr.ac.pusan.pickle.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "pickle.platform-hardware-observations.enabled=true",
        "pickle.platform-hardware-observations.token=hardware-test-only-bearer",
        "pickle.platform-hardware-observations.allowed-source-ip=127.0.0.9",
        "pickle.platform-hardware-observations.source-id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "pickle.platform-hardware-observations.expected-asset-ids=host-a,host-b",
        "server.forward-headers-strategy=none"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PlatformHardwareObservationTest {
    private static final String PATH = "/internal/platform-hardware-observations";
    private static final String SOURCE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    private String manager;
    private String admin;

    @BeforeEach
    void clearOnlySyntheticObservations() {
        jdbc.update("delete from platform_hardware_observations");
        manager = token(UserRole.SYS_MANAGER); admin = token(UserRole.SYS_ADMIN);
    }

    @Test
    void newerAutomaticReportChangesSpecsWithoutRestartOrConfiguredCapacityAndKeepsUnknownVram() throws Exception {
        Instant first = now().minusSeconds(120);
        submit(report(first, 65536)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(SOURCE))
                .andExpect(jsonPath("$.observedAt").value(first.toString()));
        String body = overview(manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.hardware.physicalHosts").value(2))
                .andExpect(jsonPath("$.hardware.cpuCores").value(12))
                .andExpect(jsonPath("$.hardware.cpuThreads").value(20))
                .andExpect(jsonPath("$.hardware.memoryMiB").value(98304))
                .andExpect(jsonPath("$.hardware.gpuDevices").value(2))
                .andExpect(jsonPath("$.hardware.dedicatedGpuMemoryMiB").isEmpty())
                .andExpect(jsonPath("$.hardware.assets[0].gpus[0].dedicatedMemoryMiB").isEmpty())
                .andExpect(jsonPath("$.hardware.assets[0].gpus[0].sharedMemory").isEmpty())
                .andExpect(jsonPath("$.hardware.assets[1].gpus[0].sharedMemory").value(true))
                .andExpect(jsonPath("$.business").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("host-a", "host-b", SOURCE, "127.0.0.9");
        submit(report(now().minusSeconds(60), 131072)).andExpect(status().isOk());
        overview(manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.hardware.memoryMiB").value(163840));
        assertThat(jdbc.queryForObject("select count(*) from platform_hardware_observations", Long.class)).isEqualTo(1);
    }

    @Test
    void oldFutureIncompleteDuplicateAndExtraFieldsCannotReplaceTheValidLatestBatch() throws Exception {
        Instant observed = now().minusSeconds(60);
        ObjectNode valid = report(observed, 65536);
        submit(valid).andExpect(status().isOk());
        submit(valid).andExpect(status().isConflict());
        submit(report(observed.minusSeconds(30), 131072)).andExpect(status().isConflict());
        submit(report(now().plusSeconds(60), 131072)).andExpect(status().isUnprocessableContent());
        ObjectNode partial = report(now().minusSeconds(10), 131072);
        partial.withArray("assets").remove(1);
        submit(partial).andExpect(status().isUnprocessableContent());
        ObjectNode duplicate = report(now().minusSeconds(10), 131072);
        ((ObjectNode) duplicate.get("assets").get(1)).put("id", "host-a");
        submit(duplicate).andExpect(status().isUnprocessableContent());
        ObjectNode extra = report(now().minusSeconds(10), 131072);
        extra.put("privateExtra", "must-not-store");
        submit(extra).andExpect(status().isUnprocessableContent());
        ObjectNode invalid = report(now().minusSeconds(10), 0);
        submit(invalid).andExpect(status().isUnprocessableContent());
        submit(report(now().minusSeconds(10), Long.MAX_VALUE)).andExpect(status().isUnprocessableContent());
        overview(manager).andExpect(status().isOk()).andExpect(jsonPath("$.hardware.memoryMiB").value(98304));
        assertThat(jdbc.queryForObject("select assets::text from platform_hardware_observations", String.class))
                .doesNotContain("privateExtra", "must-not-store");
    }

    @Test
    void dedicatedBearerActualPeerAndNoForwardedHeadersAreAllRequired() throws Exception {
        byte[] body = json.writeValueAsBytes(report(now().minusSeconds(30), 65536));
        mvc.perform(post(PATH).with(request -> { request.setRemoteAddr("127.0.0.9"); return request; })
                .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(request -> { request.setRemoteAddr("127.0.0.10"); return request; })
                .header("Authorization", "Bearer hardware-test-only-bearer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(request -> { request.setRemoteAddr("127.0.0.9"); return request; })
                .header("Authorization", "Bearer hardware-test-only-bearer").header("X-Forwarded-For", "127.0.0.9")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        ObjectNode wrongSource = report(now().minusSeconds(30), 65536);
        wrongSource.put("sourceId", UUID.randomUUID().toString());
        submit(wrongSource).andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", "Bearer hardware-test-only-bearer"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/performance/overview").header("Authorization", "Bearer hardware-test-only-bearer"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/unexpected").header("Authorization", "Bearer hardware-test-only-bearer"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from platform_hardware_observations", Long.class)).isZero();
    }

    @Test
    void bodyLimitAndStaleOrPartialStoredDataRemainUnknownWithoutHidingBusinessFacts() throws Exception {
        mvc.perform(post(PATH).with(request -> { request.setRemoteAddr("127.0.0.9"); return request; })
                .header("Authorization", "Bearer hardware-test-only-bearer").contentType(MediaType.APPLICATION_JSON)
                .content(new byte[PlatformHardwareValidator.MAX_BODY_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
        submit(report(now().minusSeconds(30), 65536)).andExpect(status().isOk());
        jdbc.update("update platform_hardware_observations set observed_at=now()-interval '16 minutes'");
        overview(admin).andExpect(status().isOk()).andExpect(jsonPath("$.hardware").isEmpty())
                .andExpect(jsonPath("$.business.registeredAccounts").isNumber());
        jdbc.update("update platform_hardware_observations set observed_at=now()-interval '30 seconds',assets=assets-1");
        overview(admin).andExpect(status().isOk()).andExpect(jsonPath("$.hardware").isEmpty())
                .andExpect(jsonPath("$.business.registeredAccounts").isNumber());
    }

    @Test
    void optionalHardwareTableFailureDoesNotAbortTheSeparateBusinessSnapshot() throws Exception {
        jdbc.execute("alter table platform_hardware_observations rename to hardware_unavailable_fixture");
        try {
            overview(admin).andExpect(status().isOk()).andExpect(jsonPath("$.hardware").isEmpty())
                    .andExpect(jsonPath("$.business.registeredAccounts").isNumber());
        } finally {
            jdbc.execute("alter table hardware_unavailable_fixture rename to platform_hardware_observations");
        }
    }

    private ResultActions submit(ObjectNode report) throws Exception {
        return mvc.perform(post(PATH).with(request -> { request.setRemoteAddr("127.0.0.9"); return request; })
                .header("Authorization", "Bearer hardware-test-only-bearer").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(report)));
    }
    private ResultActions overview(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/admin/performance/overview").header("Authorization", "Bearer " + bearer));
    }
    private ObjectNode report(Instant observedAt, long firstMemory) throws Exception {
        return (ObjectNode) json.readTree("""
                {"schemaVersion":1,"sourceId":"%s","observedAt":"%s","assets":[
                  {"id":"host-a","displayModel":"Example Compute","cpuModel":"Example CPU","cores":8,"threads":16,"memoryMiB":%d,
                   "gpus":[{"model":"Example VFIO GPU","count":1,"dedicatedMemoryMiB":null,"unifiedMemoryMiB":null}]},
                  {"id":"host-b","displayModel":"Example Unified","cpuModel":"Example CPU","cores":4,"threads":4,"memoryMiB":32768,
                   "gpus":[{"model":"Example Unified GPU","count":1,"dedicatedMemoryMiB":null,"unifiedMemoryMiB":32768}]}]}
                """.formatted(SOURCE, observedAt, firstMemory));
    }
    private String token(UserRole role) {
        User user = new User("hardware-"+UUID.randomUUID()+"@pusan.ac.kr", "{test-no-login}", "Hardware reader");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now());
        return jwt.createAccessToken(users.save(user));
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
