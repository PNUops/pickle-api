package kr.ac.pusan.pickle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
 * M2 milestone done-when proof in one flow: signup → verify (token from the
 * mock mail) → login → TEAM group → vm-request → seeded ORG_ADMIN queue →
 * approval context → approve → the JobRunr background server processes
 * MockProvisionVmJob (CREATING → RUNNING, no Proxmox) → /vms shows RUNNING →
 * audit trail exists.
 *
 * <p>The background job server is enabled just for this test (it stays off in
 * application-test.yml), so the enqueued job is genuinely picked up from the
 * jobrunr tables and executed by a worker thread — the test fails if the VM
 * stays CREATING.</p>
 */
@SpringBootTest(properties = {
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class M2EndToEndTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private static final String STUDENT_EMAIL = "m2.e2e@pusan.ac.kr";
    private static final String STUDENT_PASSWORD = "M2-e2e-Corr3ct-horse!";
    private static final String GROUP_SLUG = "m2-e2e-team";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void m2FlowFromSignupToRunningVm() throws Exception {
        // 1. signup + email verification (token comes from the mock mail)
        postJson("/api/v1/auth/signup", null,
                Map.of("email", STUDENT_EMAIL, "password", STUDENT_PASSWORD, "name", "엠투학생"))
                .andExpect(status().isAccepted());
        MailMessage mail = mockMailSender.lastMessageTo(STUDENT_EMAIL);
        assertThat(mail).as("verification mail recorded by MockMailSender").isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.body());
        assertThat(matcher.find()).isTrue();
        postJson("/api/v1/auth/verify-email", null, Map.of("token", matcher.group(1)))
                .andExpect(status().isOk());

        // 2. login as the student
        String studentToken = login(STUDENT_EMAIL, STUDENT_PASSWORD);

        // 3. create a TEAM group
        MvcResult groupResult = postJson("/api/v1/groups", studentToken,
                Map.of("kind", "TEAM", "name", "M2 종단 테스트 팀", "slug", GROUP_SLUG))
                .andExpect(status().isCreated())
                .andReturn();
        long groupId = objectMapper.readTree(groupResult.getResponse().getContentAsString())
                .get("id").asLong();

        // 4. reference data from the API: active org and template presets
        JsonNode orgs = getJson("/api/v1/orgs", studentToken);
        long orgId = findBy(orgs, "slug", "sw-edu").get("id").asLong();
        JsonNode templates = getJson("/api/v1/templates", studentToken);
        JsonNode template = findBy(templates, "name", "ubuntu-24.04");
        long templateId = template.get("id").asLong();

        // 5. submit the vm-request pre-filled with template defaults
        MvcResult requestResult = postJson("/api/v1/vm-requests", studentToken, Map.of(
                "groupId", groupId,
                "orgId", orgId,
                "templateId", templateId,
                "purpose", "M2 종단 검증용 서버",
                "reqVcpu", template.get("defaultVcpu").asInt(),
                "reqMemoryMb", template.get("defaultMemoryMb").asInt(),
                "reqDiskGb", template.get("defaultDiskGb").asInt(),
                "needSsh", true,
                "needHttp", false,
                "needPublic", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        long requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString())
                .get("id").asLong();

        // 6. the seeded ORG_ADMIN logs in and finds the request in the queue
        String adminToken = login("orgadmin@pickle.local", "pickle-orgadmin-dev!");
        mockMvc.perform(get("/api/v1/admin/vm-requests?status=SUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requestId)).exists());

        // 7. the approval context loads with all panels
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + requestId + "/context")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.email").value(STUDENT_EMAIL))
                .andExpect(jsonPath("$.group.id").value(groupId))
                .andExpect(jsonPath("$.orgHeadroom.capacity.cpuThreads").value(40))
                .andExpect(jsonPath("$.guidance").isNotEmpty());

        // 8. approve with the requested spec
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", adminToken, Map.of(
                "grantedVcpu", template.get("defaultVcpu").asInt(),
                "grantedMemoryMb", template.get("defaultMemoryMb").asInt(),
                "grantedDiskGb", template.get("defaultDiskGb").asInt(),
                "grantedTemplateId", templateId,
                "grantSsh", true,
                "grantHttp", false,
                "grantPublic", false,
                "comment", "요청 스펙 그대로 승인합니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.decision").value("APPROVE"));

        // 9. the background job server processes MockProvisionVmJob:
        //    /vms must show RUNNING — the test fails if the VM stays CREATING
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/vms?groupId=" + groupId)
                                .header("Authorization", "Bearer " + studentToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.content[0].status").value("RUNNING")));

        // 10. VM summary/detail carry the granted spec and the mock status note
        JsonNode vms = getJson("/api/v1/vms", studentToken);
        JsonNode vm = vms.get("content").get(0);
        assertThat(vm.get("hostname").asString()).startsWith(GROUP_SLUG + "-");
        assertThat(vm.get("requestId").asLong()).isEqualTo(requestId);
        assertThat(vm.get("statusDetail").asString()).isEqualTo("모의 프로비저닝 완료");
        mockMvc.perform(get("/api/v1/vms/" + vm.get("id").asLong())
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.sshUsername").value("student"))
                .andExpect(jsonPath("$.orgId").value(orgId))
                .andExpect(jsonPath("$.ipAddress").value((Object) null));

        // 11. the audit trail covers the whole flow
        for (String action : new String[] {"auth.signup", "auth.verify", "auth.login",
                "group.create", "request.create", "request.approve"}) {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from audit_logs where action = ?", Long.class, action);
            assertThat(count).as("audit rows for %s", action).isPositive();
        }
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
