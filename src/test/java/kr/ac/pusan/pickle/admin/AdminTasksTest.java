package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SYS_ADMIN task queue (contract {@code listAdminTasks}/{@code retryAdminTask}):
 * VM/org join fields, status/kind/vmId filters, newest-updated ordering, and
 * the retry CAS matrix (202 NEEDS_ADMIN→RETRYING with attempts/error reset,
 * 409 otherwise, 404 unknown, audited). The JobRunr background server is off
 * so the CAS result stays observable (job execution is covered by the
 * pipeline tests).
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminTasksTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String orgAdminToken;
    private long orgId;
    private String orgName;
    private long vmA;
    private long vmB;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("admin@pickle.local").orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("orgadmin@pickle.local").orElseThrow());
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        orgName = jdbcTemplate.queryForObject("select name from orgs where id = ?", String.class, orgId);
        long groupId = createGroup();
        vmA = createVm(groupId);
        vmB = createVm(groupId);
    }

    @Test
    void listsTasksWithVmAndOrgContextAndFilters() throws Exception {
        long needsAdmin = createTask(vmA, "PROVISION", "NEEDS_ADMIN", 3, "테스트 오류");
        long done = createTask(vmB, "DELETE", "DONE", 0, null);

        mockMvc.perform(get("/api/v1/admin/tasks")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/tasks")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byTaskId(needsAdmin)).exists())
                .andExpect(jsonPath(byTaskId(done)).exists());

        mockMvc.perform(get("/api/v1/admin/tasks?status=NEEDS_ADMIN&vmId=" + vmA)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].taskId").value(needsAdmin))
                .andExpect(jsonPath("$.content[0].vmId").value(vmA))
                .andExpect(jsonPath("$.content[0].vmName").isNotEmpty())
                .andExpect(jsonPath("$.content[0].hostname").isNotEmpty())
                .andExpect(jsonPath("$.content[0].orgId").value(orgId))
                .andExpect(jsonPath("$.content[0].orgName").value(orgName))
                .andExpect(jsonPath("$.content[0].kind").value("PROVISION"))
                .andExpect(jsonPath("$.content[0].status").value("NEEDS_ADMIN"))
                .andExpect(jsonPath("$.content[0].currentStep").value(3))
                .andExpect(jsonPath("$.content[0].totalSteps").value(11))
                .andExpect(jsonPath("$.content[0].stepLabel").isNotEmpty())
                .andExpect(jsonPath("$.content[0].lastError").value("테스트 오류"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/tasks?kind=DELETE&vmId=" + vmB)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byTaskId(done)).exists())
                .andExpect(jsonPath(byTaskId(needsAdmin)).doesNotExist());
    }

    @Test
    void retryIsAcceptedOnlyForNeedsAdminAndResetsTheAttemptBudget() throws Exception {
        long parked = createTask(vmA, "PROVISION", "NEEDS_ADMIN", 5, "IP 풀 고갈");
        long enqueuedBefore = provisionEnqueueCount();

        mockMvc.perform(post("/api/v1/admin/tasks/{id}/retry", parked)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // the afterCommit dispatch really enqueued the pipeline job (the
        // background server is off, so the row is the observable evidence)
        assertThat(provisionEnqueueCount()).isEqualTo(enqueuedBefore + 1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status::text as status, attempts, last_error, current_step"
                        + " from provisioning_tasks where id = ?", parked);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("last_error")).isNull();
        assertThat(((Number) row.get("current_step")).intValue()).isEqualTo(5); // resumes in place

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'task.retry' and target_id = ?",
                Long.class, parked)).isEqualTo(1);

        // now RETRYING → a second retry answers 409 (idempotence by CAS)
        mockMvc.perform(post("/api/v1/admin/tasks/{id}/retry", parked)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));
    }

    @Test
    void retryAnswers409ForFinishedTasks404ForUnknownAnd403ForOrgAdmin() throws Exception {
        long doneTask = createTask(vmB, "DELETE", "DONE", 0, null);

        mockMvc.perform(post("/api/v1/admin/tasks/{id}/retry", doneTask)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));

        mockMvc.perform(post("/api/v1/admin/tasks/999999/retry")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/admin/tasks/{id}/retry", doneTask)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
    }

    // --- fixtures ---------------------------------------------------------------

    private static String byTaskId(long taskId) {
        return "$.content[?(@.taskId == %d)]".formatted(taskId);
    }

    private long createGroup() {
        String slug = "adt-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    private long createVm(long groupId) {
        long templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        long requesterId = jdbcTemplate.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '작업 큐 테스트', ?, 2, 2048, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "adt-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'STOPPED'::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }

    /** JobRunr rows of the provision pipeline job (enqueue evidence). */
    private long provisionEnqueueCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%provisionVm(%'",
                Long.class);
    }

    private long createTask(long vmId, String kind, String status, int currentStep,
            String lastError) {
        return jdbcTemplate.queryForObject("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts,
                                                last_error)
                values (?, ?::provisioning_task_kind, ?, ?::provisioning_task_status, 3, ?)
                returning id
                """, Long.class, vmId, kind, currentStep, status, lastError);
    }
}
