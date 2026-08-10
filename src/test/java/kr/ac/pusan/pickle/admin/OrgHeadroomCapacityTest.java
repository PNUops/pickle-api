package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Disk is the one capacity figure that is measured per node rather than
 * declared with it, so the platform sum is only meaningful once every ACTIVE
 * node has been measured. A partial sum would read as less headroom than there
 * is, which is worse than no number at all.
 */
@ExtendWith(MockitoExtension.class)
class OrgHeadroomCapacityTest {

    @Mock
    private VmRepository vmRepository;
    @Mock
    private NodeRepository nodeRepository;
    @Mock
    private SettingsService settingsService;

    @Test
    void diskCapacitySumsOnlyWhenEveryActiveNodeHasBeenMeasured() {
        List<Node> nodes = List.of(node(40, 79872, 1600L), node(24, 32768, 800L));
        when(nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE)).thenReturn(nodes);

        OrgHeadroomService.PlatformCapacity capacity = service().capacity();

        assertThat(capacity.cpuThreads()).isEqualTo(64);
        assertThat(capacity.memoryMb()).isEqualTo(112_640);
        assertThat(capacity.diskGb()).isEqualTo(2400L);
    }

    @Test
    void oneUnmeasuredNodeLeavesTheWholeDiskDenominatorUnknown() {
        List<Node> nodes = List.of(node(40, 79872, 1600L), node(24, 32768, null));
        when(nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE)).thenReturn(nodes);

        OrgHeadroomService.PlatformCapacity capacity = service().capacity();

        assertThat(capacity.cpuThreads()).isEqualTo(64);
        assertThat(capacity.diskGb()).isNull();
    }

    @Test
    void noActiveNodeIsNotAZeroSizedPool() {
        when(nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE)).thenReturn(List.of());

        assertThat(service().capacity().diskGb()).isNull();
    }

    private static Node node(int cpuThreads, int memoryMb, Long diskCapacityGb) {
        Node node = mock(Node.class);
        when(node.getCpuThreads()).thenReturn(cpuThreads);
        when(node.getMemoryMb()).thenReturn(memoryMb);
        when(node.getDiskCapacityGb()).thenReturn(diskCapacityGb);
        return node;
    }

    private OrgHeadroomService service() {
        return new OrgHeadroomService(vmRepository, nodeRepository, settingsService);
    }
}
