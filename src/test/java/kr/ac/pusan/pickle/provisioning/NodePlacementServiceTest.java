package kr.ac.pusan.pickle.provisioning;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Forced-node placement backstop: an admin-forced node
 * must host an ACTIVE copy of the granted image, or placement fails the
 * same way the no-candidate auto path does — cleanly at the place step,
 * never proceeding to a clone that would fail mid-pipeline. All rows are
 * created on dedicated nodes/images so the shared seed stays untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NodePlacementServiceTest {

    @Autowired
    private NodePlacementService placementService;

    @Autowired
    private OsImageRepository imageRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long requesterId;
    private long workspaceId;
    private String imageName;
    private OsImage image;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbc);
        requesterId = SeedFixtures.orgadminId(jdbc);
        String slug = "place-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbc.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        imageName = "place-tmpl-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void forcedNodeHostingTheImageIsChosen() {
        long nodeId = insertNode();
        image = insertImage(nodeId, "ACTIVE");
        Vm vm = vm(nodeId);

        Node placed = placementService.place(vm, image, nodeId);

        assertThat(placed.getId()).isEqualTo(nodeId);
    }

    @Test
    void forcedNodeWithoutTheImageFailsPlacement() {
        long imageNodeId = insertNode();
        image = insertImage(imageNodeId, "ACTIVE");
        // a different ACTIVE node that does not host the granted image
        long otherNodeId = insertNode();
        Vm vm = vm(otherNodeId);

        assertThatThrownBy(() -> placementService.place(vm, image, otherNodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(imageName);
    }

    @Test
    void forcedNodeWhoseImageWasDeactivatedFailsPlacement() {
        // the approval-time check passed, then the image was DISABLED on the
        // node before provisioning — the backstop must still refuse it.
        long nodeId = insertNode();
        image = insertImage(nodeId, "DISABLED");
        Vm vm = vm(nodeId);

        assertThatThrownBy(() -> placementService.place(vm, image, nodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(imageName);
    }

    private long insertNode() {
        return jdbc.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, 'https://172.30.0.9:8006', 8, 16384, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, "place-node-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private OsImage insertImage(long nodeId, String status) {
        long id = jdbc.queryForObject("""
                insert into os_images (name, display_name, os_family, os_version,
                                          ssh_username, proxmox_vmid, node_id,
                                          min_disk_gb, status)
                values (?, '배치 테스트 OS 이미지', 'ubuntu', '24.04', 'ubuntu', 1003, ?, 10,
                        cast(? as catalog_status))
                returning id
                """, Long.class, imageName, nodeId, status);
        return imageRepository.findById(id).orElseThrow();
    }

    private Vm vm(long nodeId) {
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, requesterId, "배치 테스트", image.getId(), 1, 1024, 10);
        String hostname = "place-vm-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                image.getId());
        return vmRepository.findById(vmId).orElseThrow();
    }
}
