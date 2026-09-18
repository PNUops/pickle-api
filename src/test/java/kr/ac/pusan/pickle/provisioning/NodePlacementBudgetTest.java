package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.provisioning.NodePlacementBudget.VmPlacementResources;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NodePlacementBudgetTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @Test
    void coreReservesAreExcludedExactlyOnceAndEachDimensionIsAHardLimit() {
        Node node = node(true);
        NodePlacementBudget budget = NodePlacementBudget.from(node, NOW);
        assertThat(budget.reservationAware()).isTrue();
        assertThat(budget.fits(new VmPlacementResources(27, 57000, 790), new VmPlacementResources(1, 344, 10))).isTrue();
        assertThat(budget.fits(new VmPlacementResources(28, 1, 1), new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(budget.fits(new VmPlacementResources(1, 57344, 1), new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(budget.fits(new VmPlacementResources(1, 1, 800), new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(node.getCpuThreads()).isEqualTo(32);
        assertThat(node.getMemoryMb()).isEqualTo(57344);
        assertThat(node.getDiskCapacityGb()).isEqualTo(1000);
    }

    @Test
    void legacyCpuAndAggregateThinDiskPoliciesRemainAdvisory() {
        NodePlacementBudget budget = NodePlacementBudget.from(node(false), NOW);
        assertThat(budget.reservationAware()).isFalse();
        assertThat(budget.fits(new VmPlacementResources(100, 1000, 5000), new VmPlacementResources(100, 1000, 100))).isTrue();
        assertThat(budget.fits(new VmPlacementResources(0, 57344, 0), new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(budget.fits(new VmPlacementResources(0, 0, 0), new VmPlacementResources(1, 1, 1001))).isFalse();
    }

    @Test
    void overflowOrNegativeAccountingCannotProduceFalseHeadroom() {
        NodePlacementBudget budget = NodePlacementBudget.from(node(true), NOW);
        assertThat(budget.fits(new VmPlacementResources(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(budget.fits(new VmPlacementResources(-1, 0, 0), new VmPlacementResources(1, 1, 1))).isFalse();
        assertThat(budget.fits(new VmPlacementResources(0, 0, 0), new VmPlacementResources(0, 1, 1))).isFalse();
    }

    @Test
    void malformedReservationMetadataDoesNotSilentlyBecomeALegacyNode() {
        Node node = node(false);
        ReflectionTestUtils.setField(node, "labels", Map.of("placement_capacity", Map.of("schema_version", 99)));
        assertThatThrownBy(() -> NodePlacementBudget.from(node, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private Node node(boolean reserved) {
        Node node = org.springframework.beans.BeanUtils.instantiateClass(Node.class);
        ReflectionTestUtils.setField(node, "cpuThreads", 32);
        ReflectionTestUtils.setField(node, "memoryMb", 57344);
        ReflectionTestUtils.setField(node, "diskCapacityGb", 1000L);
        if (reserved) {
            ReflectionTestUtils.setField(node, "labels", Map.of("placement_capacity", Map.of(
                    "schema_version", 1, "measured_at", NOW.toString(),
                    "physical", Map.of("cpu_threads", 32, "memory_mb", 65536, "disk_gb", 1000),
                    "reserved", Map.of("cpu_threads", 4, "memory_mb", 8192, "disk_gb", 200),
                    "allocatable", Map.of("cpu_threads", 28, "memory_mb", 57344, "disk_gb", 800))));
        }
        return node;
    }
}
