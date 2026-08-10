package kr.ac.pusan.pickle.provisioning;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Crash recovery for stranded pipelines ({@link StaleTaskRecoveryJob}):
 * RUNNING tasks abandoned by a dead worker are reclaimed to RETRYING and
 * re-enqueued, lost PENDING/RETRYING runs are re-enqueued as-is, CREATING
 * VMs whose after-commit enqueue was lost get a fresh PROVISION job, and
 * fresh or NEEDS_ADMIN tasks are never touched. The JobRunr background
 * server is off in tests, so enqueues are observed as jobrunr_jobs rows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class StaleTaskRecoveryJobTest {

    @Autowired
    private StaleTaskRecoveryJob recoveryJob;

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long nodeId;
    private long imageId;
    private long requesterId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbc);
        nodeId = jdbc.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbc.queryForObject("select min(id) from os_images", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbc);
        String slug = "stale-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbc.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    @Test
    void reclaimsStaleRunningProvisionTaskAndReenqueues() {
        long vmId = createVm("CREATING");
        long taskId = insertTask(vmId, "PROVISION", "RUNNING", 2, "40 minutes");

        recoveryJob.recover();

        assertThat(taskStatus(taskId)).isEqualTo("RETRYING");
        assertThat(jdbc.queryForObject(
                "select last_error from provisioning_tasks where id = ?", String.class, taskId))
                .contains("스테일");
        assertThat(enqueued("provisionVm", vmId)).isEqualTo(1);
    }

    @Test
    void reenqueuesLostPendingAndRetryingRunsWithoutStatusChange() {
        long pendingVm = createVm("CREATING");
        long pendingTask = insertTask(pendingVm, "PROVISION", "PENDING", 0, "40 minutes");
        long retryingVm = createVm("CREATING");
        long retryingTask = insertTask(retryingVm, "PROVISION", "RETRYING", 1, "40 minutes");

        recoveryJob.recover();

        assertThat(taskStatus(pendingTask)).isEqualTo("PENDING");
        assertThat(taskStatus(retryingTask)).isEqualTo("RETRYING");
        assertThat(enqueued("provisionVm", pendingVm)).isEqualTo(1);
        assertThat(enqueued("provisionVm", retryingVm)).isEqualTo(1);
    }

    @Test
    void staleDeleteTaskIsReclaimedAndReenqueued() {
        long vmId = createVm("DELETING");
        long taskId = insertTask(vmId, "DELETE", "RUNNING", 1, "40 minutes");

        recoveryJob.recover();

        assertThat(taskStatus(taskId)).isEqualTo("RETRYING");
        assertThat(enqueued("deleteVm", vmId)).isEqualTo(1);
    }

    @Test
    void freshAndParkedTasksAreLeftAlone() {
        long freshVm = createVm("CREATING");
        long freshTask = insertTask(freshVm, "PROVISION", "RUNNING", 1, "1 minute");
        long parkedVm = createVm("CREATING");
        long parkedTask = insertTask(parkedVm, "PROVISION", "NEEDS_ADMIN", 4, "40 minutes");

        recoveryJob.recover();

        assertThat(taskStatus(freshTask)).isEqualTo("RUNNING");
        assertThat(taskStatus(parkedTask)).isEqualTo("NEEDS_ADMIN");
        assertThat(enqueued("provisionVm", freshVm)).isZero();
        assertThat(enqueued("provisionVm", parkedVm)).isZero();
    }

    @Test
    void reenqueuesStuckCreatingVmWithoutAnyProvisionTask() {
        // approve committed, after-commit enqueue lost: CREATING and taskless
        long orphanVm = createVm("CREATING");
        jdbc.update("update vms set updated_at = now() - interval '40 minutes' where id = ?",
                orphanVm);
        // a CREATING VM with a live (fresh) task must not get a duplicate job
        long ownedVm = createVm("CREATING");
        jdbc.update("update vms set updated_at = now() - interval '40 minutes' where id = ?",
                ownedVm);
        insertTask(ownedVm, "PROVISION", "RUNNING", 1, "1 minute");

        recoveryJob.recover();

        assertThat(enqueued("provisionVm", orphanVm)).isEqualTo(1);
        assertThat(enqueued("provisionVm", ownedVm)).isZero();
    }

    @Test
    void releasesStalePowerActionClaimButKeepsFreshOnes() {
        // a crashed power worker left the claim set beyond the 10-minute cutoff
        long staleVm = createVm("STOPPED");
        jdbc.update("""
                update vms set pending_power_action = 'START',
                       pending_power_action_at = now() - interval '15 minutes' where id = ?
                """, staleVm);
        // an in-flight claim within the window must survive
        long freshVm = createVm("RUNNING");
        jdbc.update("""
                update vms set pending_power_action = 'SHUTDOWN',
                       pending_power_action_at = now() - interval '2 minutes' where id = ?
                """, freshVm);

        recoveryJob.recover();

        assertThat(pendingAction(staleVm)).isNull();
        assertThat(pendingAction(freshVm)).isEqualTo("SHUTDOWN");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String pendingAction(long vmId) {
        return jdbc.queryForObject("select pending_power_action from vms where id = ?",
                String.class, vmId);
    }

    private long createVm(String status) {
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, requesterId, "스테일 회수 테스트", imageId, 1, 1024, 10);
        String hostname = "stale-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, status);
    }

    private long insertTask(long vmId, String kind, String status, int attempts, String age) {
        return jdbc.queryForObject("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts,
                                                updated_at)
                values (?, ?::provisioning_task_kind, 4, ?::provisioning_task_status, ?,
                        now() - ?::interval)
                returning id
                """, Long.class, vmId, kind, status, attempts, age);
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("select status from provisioning_tasks where id = ?",
                String.class, taskId);
    }

    /** The signature carries types only; the vmId is in the serialized job. */
    private long enqueued(String method, long vmId) {
        return jdbc.queryForObject("""
                select count(*) from jobrunr_jobs
                 where jobasjson like ? and jobasjson like ?
                """, Long.class,
                "%\"methodName\":\"" + method + "\"%", "%\"object\":" + vmId + "%");
    }
}
