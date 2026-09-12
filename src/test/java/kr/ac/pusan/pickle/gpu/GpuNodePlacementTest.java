package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.provisioning.NodePlacementService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.Test;

class GpuNodePlacementTest {
    @Test
    void ordinaryCapacityWinsEvenWhenAGpuNodeHasABetterScore() {
        var fixture = fixture(8192);
        assertThat(fixture.service().place(fixture.vm(), fixture.image(), null).getId()).isEqualTo(1L);
    }
    @Test
    void gpuCapacityIsUsedWhenOrdinaryNodesCannotFit() {
        var fixture = fixture(512);
        assertThat(fixture.service().place(fixture.vm(), fixture.image(), null).getId()).isEqualTo(2L);
    }
    private Fixture fixture(int ordinaryMemory) {
        NodeRepository nodes = mock(NodeRepository.class);
        OsImageRepository images = mock(OsImageRepository.class);
        VmRepository vms = mock(VmRepository.class);
        Node ordinary = node(1, false, ordinaryMemory);
        Node gpu = node(2, true, 65536);
        when(nodes.findByStatusOrderByIdAsc(NodeStatus.ACTIVE)).thenReturn(List.of(ordinary, gpu));
        OsImage first = image(1); OsImage second = image(2);
        when(images.findByStatus(CatalogStatus.ACTIVE)).thenReturn(List.of(first, second));
        VmRepository.AllocatedCapacity used = mock(VmRepository.AllocatedCapacity.class);
        when(vms.sumActiveByNodeId(anyLong(), any())).thenReturn(used);
        Vm vm = mock(Vm.class); when(vm.getMemoryMb()).thenReturn(1024);
        return new Fixture(new NodePlacementService(nodes, images, vms), vm, first);
    }
    private Node node(long id, boolean gpu, int memory) {
        Node node = mock(Node.class); when(node.getId()).thenReturn(id); when(node.isGpuNode()).thenReturn(gpu);
        when(node.getMemoryMb()).thenReturn(memory); when(node.getCpuThreads()).thenReturn(8); return node;
    }
    private OsImage image(long node) {
        OsImage image = mock(OsImage.class); when(image.getName()).thenReturn("example-os"); when(image.getNodeId()).thenReturn(node); return image;
    }
    private record Fixture(NodePlacementService service, Vm vm, OsImage image) {}
}
