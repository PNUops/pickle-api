package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

class VmCloneReservationServiceTest {

    private final OsImageRepository images = mock(OsImageRepository.class);
    private final NodeRepository nodes = mock(NodeRepository.class);
    private final VmRepository vms = mock(VmRepository.class);
    private final VmCloneReservationService service = new VmCloneReservationService(images, nodes, vms);

    private Node first;
    private Node second;
    private OsImage canonical;
    private OsImage replica;

    @BeforeEach
    void setUp() {
        first = node(1, "first", true);
        second = node(2, "second", true);
        canonical = image(10, first.getId(), 1000, CatalogStatus.DISABLED);
        replica = image(11, second.getId(), 1001, CatalogStatus.ACTIVE);
        when(images.findByPublicId(canonical.getPublicId())).thenReturn(java.util.Optional.of(canonical));
        when(images.findRevisionForUpdate(canonical.getName(), canonical.getVersion()))
                .thenReturn(List.of(canonical, replica));
        when(nodes.findAllByIdForUpdate(List.of(second.getId()))).thenReturn(List.of(second));
        when(vms.sumActiveByNodeId(second.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 0, 0));
    }

    @Test
    void disabledOriginalIdentityPinsAnAvailableCompatibleReplica() {
        VmCloneReservationService.Reservation result = service.reserve(canonical.getPublicId(), null,
                new NodePlacementBudget.VmPlacementResources(2, 2048, 20));

        assertThat(result.canonical()).isSameAs(canonical);
        assertThat(result.replica()).isSameAs(replica);
        assertThat(result.node()).isSameAs(second);
        assertThat(result.pin().imageId()).isEqualTo(replica.getId());
        assertThat(result.pin().templateVmid()).isEqualTo(replica.getProxmoxVmid());
    }

    @Test
    void forcedNodeCannotEscapeTheLockedEligibleReplicaSet() {
        assertThatThrownBy(() -> service.reserve(canonical.getPublicId(), first.getPublicId(),
                new NodePlacementBudget.VmPlacementResources(1, 1024, 10)))
                .isInstanceOf(VmCloneReservationService.NoCapacityException.class)
                .hasMessageContaining("수용할 수 있는 노드");
    }

    @Test
    void reservedCapacityIsCheckedAgainstCommittedVmIntent() {
        when(vms.sumActiveByNodeId(second.getId(), VmStatus.DELETED)).thenReturn(allocated(27, 57000, 790));

        assertThatThrownBy(() -> service.reserve(canonical.getPublicId(), null,
                new NodePlacementBudget.VmPlacementResources(2, 344, 10)))
                .isInstanceOf(VmCloneReservationService.NoCapacityException.class)
                .hasMessageContaining("수용할 수 있는 노드");
    }

    @Test
    void mutableRevisionMetadataCannotBecomeAnEligibleReplica() {
        ReflectionTestUtils.setField(replica, "sshUsername", "another-user");

        assertThatThrownBy(() -> service.reserve(canonical.getPublicId(), null,
                new NodePlacementBudget.VmPlacementResources(1, 1024, 10)))
                .isInstanceOf(VmCloneReservationService.NoCapacityException.class);
    }

    private static Node node(long id, String name, boolean prepared) {
        Node node = BeanUtils.instantiateClass(Node.class);
        ReflectionTestUtils.setField(node, "id", id);
        ReflectionTestUtils.setField(node, "name", name);
        ReflectionTestUtils.setField(node, "status", NodeStatus.ACTIVE);
        ReflectionTestUtils.setField(node, "cpuThreads", 32);
        ReflectionTestUtils.setField(node, "memoryMb", 57344);
        ReflectionTestUtils.setField(node, "diskCapacityGb", 1000L);
        if (prepared) {
            ReflectionTestUtils.setField(node, "labels", Map.of("placement_capacity", Map.of(
                    "schema_version", 1, "measured_at", Instant.parse("2026-09-18T00:00:00Z").toString(),
                    "physical", Map.of("cpu_threads", 32, "memory_mb", 65536, "disk_gb", 1000),
                    "reserved", Map.of("cpu_threads", 4, "memory_mb", 8192, "disk_gb", 200),
                    "allocatable", Map.of("cpu_threads", 28, "memory_mb", 57344, "disk_gb", 800))));
        }
        return node;
    }

    private static OsImage image(long id, long nodeId, int vmid, CatalogStatus status) {
        OsImage image = new OsImage("example-os", "Example OS", "ubuntu", "26.04", "ubuntu",
                vmid, nodeId, 1, 10, status, null);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    private static VmRepository.AllocatedCapacity allocated(long cpu, long memory, long disk) {
        return new VmRepository.AllocatedCapacity() {
            @Override
            public long getVcpu() {
                return cpu;
            }

            @Override
            public long getMemoryMb() {
                return memory;
            }

            @Override
            public long getDiskGb() {
                return disk;
            }
        };
    }
}
