package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmDeleteKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V6 lifecycle schema behavior: the partial unique index allows at most one
 * live provisioning task per (vm, kind) while finished tasks do not block,
 * the CAS transition methods are idempotent under re-runs, vm_events append
 * and read back, and the new vms lifecycle columns map onto the entity.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ProvisioningLifecycleTest {

    @Autowired
    private ProvisioningTaskRepository taskRepository;

    @Autowired
    private VmEventRepository vmEventRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long nodeId;
    private long templateId;
    private long requesterId;
    private long groupId;

    @BeforeEach
    void setUp() {
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        requesterId = jdbcTemplate.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        String slug = "lifec-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id
                """, Long.class, slug, slug);
    }

    @Test
    void atMostOneLiveTaskPerVmAndKind() {
        long vmId = createVm();
        ProvisioningTask task = taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION));
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.PENDING);
        assertThat(task.getCurrentStep()).isZero();
        assertThat(task.getAttempts()).isZero();

        // duplicate approve / duplicate job → the partial unique index refuses
        assertThatThrownBy(() -> taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // a different kind for the same VM is its own guard scope
        taskRepository.saveAndFlush(new ProvisioningTask(vmId, ProvisioningTaskKind.DELETE));

        // still live while RUNNING and NEEDS_ADMIN
        assertThat(taskRepository.startAttempt(task.getId(), Instant.now())).isEqualTo(1);
        assertThatThrownBy(() -> taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(taskRepository.park(task.getId(), "boom", Instant.now())).isEqualTo(1);
        assertThatThrownBy(() -> taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // DONE frees the slot: e.g. a re-provision after admin resolution
        jdbcTemplate.update("update provisioning_tasks set status = 'DONE' where id = ?",
                task.getId());
        ProvisioningTask again = taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION));
        assertThat(again.getId()).isNotEqualTo(task.getId());
    }

    @Test
    void casTransitionsAreIdempotentUnderReRuns() {
        long vmId = createVm();
        Long id = taskRepository.saveAndFlush(
                new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION)).getId();
        Instant now = Instant.now();

        // PENDING → RUNNING once; the duplicate run sees 0 rows and stops
        assertThat(taskRepository.startAttempt(id, now)).isEqualTo(1);
        assertThat(taskRepository.startAttempt(id, now)).isZero();
        ProvisioningTask task = taskRepository.findById(id).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.RUNNING);
        assertThat(task.getAttempts()).isEqualTo(1);

        // step pointer advances exactly once per step
        assertThat(taskRepository.advanceStep(id, 0, now)).isEqualTo(1);
        assertThat(taskRepository.advanceStep(id, 0, now)).isZero();
        assertThat(taskRepository.findById(id).orElseThrow().getCurrentStep()).isEqualTo(1);

        // retry loop: RUNNING → RETRYING → RUNNING counts the second attempt
        assertThat(taskRepository.markRetrying(id, "timeout", now)).isEqualTo(1);
        assertThat(taskRepository.findById(id).orElseThrow().getLastError()).isEqualTo("timeout");
        assertThat(taskRepository.resumeAttempt(id, now)).isEqualTo(1);
        assertThat(taskRepository.findById(id).orElseThrow().getAttempts()).isEqualTo(2);

        assertThat(taskRepository.attachJobrunrJob(id, "job-123", now)).isEqualTo(1);
        assertThat(taskRepository.findById(id).orElseThrow().getJobrunrJobId()).isEqualTo("job-123");

        // finish: RUNNING → DONE clears the error; repeat and park are no-ops
        assertThat(taskRepository.complete(id, now)).isEqualTo(1);
        assertThat(taskRepository.complete(id, now)).isZero();
        assertThat(taskRepository.park(id, "late", now)).isZero();
        task = taskRepository.findById(id).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(ProvisioningTaskStatus.DONE);
        assertThat(task.getLastError()).isNull();
    }

    @Test
    void vmEventsAppendAndLifecycleColumnsMapOntoTheEntity() {
        long vmId = createVm();
        vmEventRepository.save(new VmEvent(vmId, VmEventType.CREATE, requesterId, "생성됨"));
        // system actor (sweeper/reconciler) is null; new v0.3.x event types work
        vmEventRepository.save(new VmEvent(vmId, VmEventType.SCHEDULE_DELETE, null, null));

        var page = vmEventRepository.findByVmIdOrderByIdDesc(vmId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().getFirst().getType()).isEqualTo(VmEventType.SCHEDULE_DELETE);
        assertThat(page.getContent().getFirst().getActorId()).isNull();
        assertThat(page.getContent().getLast().getType()).isEqualTo(VmEventType.CREATE);
        assertThat(page.getContent().getLast().getActorId()).isEqualTo(requesterId);
        assertThat(page.getContent().getLast().getDetail()).isEqualTo("생성됨");

        jdbcTemplate.update("""
                update vms
                   set initial_password_enc = 'v1:iv:ct', initial_password_hash = 'bcrypt-hash',
                       delete_kind = 'SELF', delete_scheduled_for = now() + interval '168 hours',
                       delete_requested_by = ?, delete_reason = '학기 종료'
                 where id = ?
                """, requesterId, vmId);
        Vm vm = vmRepository.findById(vmId).orElseThrow();
        assertThat(vm.getInitialPasswordEnc()).isEqualTo("v1:iv:ct");
        assertThat(vm.getInitialPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(vm.getInitialPasswordViewedAt()).isNull();
        assertThat(vm.getDeleteKind()).isEqualTo(VmDeleteKind.SELF);
        assertThat(vm.getDeleteScheduledFor()).isAfter(Instant.now());
        assertThat(vm.getDeleteRequestedBy()).isEqualTo(requesterId);
        assertThat(vm.getDeleteReason()).isEqualTo("학기 종료");
    }

    /** Minimal request→vm graph for the FK chains under test. */
    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '수명주기 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "lifec-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, templateId);
    }
}
