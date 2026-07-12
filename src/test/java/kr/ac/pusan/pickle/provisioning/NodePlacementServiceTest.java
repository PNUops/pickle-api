package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Forced-node placement backstop (review finding A3): an admin-forced node
 * must host an ACTIVE copy of the granted template, or placement fails the
 * same way the no-candidate auto path does — cleanly at the place step,
 * never proceeding to a clone that would fail mid-pipeline. All rows are
 * created on dedicated nodes/templates so the shared seed stays untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NodePlacementServiceTest {

    @Autowired
    private NodePlacementService placementService;

    @Autowired
    private VmTemplateRepository templateRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long requesterId;
    private long groupId;
    private String templateName;
    private VmTemplate template;

    @BeforeEach
    void setUp() {
        orgId = jdbc.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        requesterId = jdbc.queryForObject(
                "select id from users where email = 'orgadmin@pickle.local'", Long.class);
        String slug = "place-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbc.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        templateName = "place-tmpl-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void forcedNodeHostingTheTemplateIsChosen() {
        long nodeId = insertNode();
        template = insertTemplate(nodeId, "ACTIVE");
        Vm vm = vm(nodeId);

        Node placed = placementService.place(vm, template, nodeId);

        assertThat(placed.getId()).isEqualTo(nodeId);
    }

    @Test
    void forcedNodeWithoutTheTemplateFailsPlacement() {
        long templateNodeId = insertNode();
        template = insertTemplate(templateNodeId, "ACTIVE");
        // a different ACTIVE node that does not host the granted template
        long otherNodeId = insertNode();
        Vm vm = vm(otherNodeId);

        assertThatThrownBy(() -> placementService.place(vm, template, otherNodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(templateName);
    }

    @Test
    void forcedNodeWhoseTemplateWasDeactivatedFailsPlacement() {
        // the approval-time check passed, then the template was DISABLED on the
        // node before provisioning — the backstop must still refuse it.
        long nodeId = insertNode();
        template = insertTemplate(nodeId, "DISABLED");
        Vm vm = vm(nodeId);

        assertThatThrownBy(() -> placementService.place(vm, template, nodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(templateName);
    }

    private long insertNode() {
        return jdbc.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, 'https://172.30.0.9:8006', 8, 16384, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, "place-node-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private VmTemplate insertTemplate(long nodeId, String status) {
        long id = jdbc.queryForObject("""
                insert into vm_templates (name, display_name, proxmox_vmid, node_id,
                                          default_vcpu, default_memory_mb, default_disk_gb,
                                          min_disk_gb, status)
                values (?, '배치 테스트 템플릿', 9000, ?, 1, 1024, 10, 10, cast(? as template_status))
                returning id
                """, Long.class, templateName, nodeId, status);
        return templateRepository.findById(id).orElseThrow();
    }

    private Vm vm(long nodeId) {
        long requestId = jdbc.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '배치 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, template.getId());
        String hostname = "place-vm-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbc.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                template.getId());
        return vmRepository.findById(vmId).orElseThrow();
    }
}
