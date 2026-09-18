package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.PlacementCapacity;
import org.jspecify.annotations.Nullable;

/** Capacity arithmetic only; callers must serialize the allocation read and reservation write. */
public final class NodePlacementBudget {

    private final long cpuThreads;
    private final long memoryMb;
    private final @Nullable Long diskGb;
    private final boolean reservationAware;

    private NodePlacementBudget(long cpuThreads, long memoryMb, @Nullable Long diskGb, boolean reservationAware) {
        if (cpuThreads <= 0 || memoryMb <= 0 || (diskGb != null && diskGb <= 0)) {
            throw new IllegalStateException("노드의 배치 가능 용량을 확인할 수 없습니다.");
        }
        this.cpuThreads = cpuThreads;
        this.memoryMb = memoryMb;
        this.diskGb = diskGb;
        this.reservationAware = reservationAware;
    }

    public static NodePlacementBudget from(Node node, Instant observedAt) {
        return node.placementCapacity(observedAt).map(capacity -> {
            PlacementCapacity.CapacityAmounts available = capacity.allocatable();
            return new NodePlacementBudget(available.cpuThreads(), available.memoryMb(), available.diskGb(), true);
        }).orElseGet(() -> new NodePlacementBudget(node.getCpuThreads(), node.getMemoryMb(), node.getDiskCapacityGb(), false));
    }

    public boolean fits(VmPlacementResources allocated, VmPlacementResources requested) {
        if (allocated.cpuThreads() < 0 || allocated.memoryMb() < 0 || allocated.diskGb() < 0
                || requested.cpuThreads() <= 0 || requested.memoryMb() <= 0 || requested.diskGb() <= 0) {
            return false;
        }
        if (!fitsDimension(memoryMb, allocated.memoryMb(), requested.memoryMb())) {
            return false;
        }
        if (reservationAware) {
            return fitsDimension(cpuThreads, allocated.cpuThreads(), requested.cpuThreads())
                    && fitsDimension(diskGb, allocated.diskGb(), requested.diskGb());
        }
        // A legacy disk total remains advisory for aggregate use, but one disk must fit that pool.
        return diskGb == null || requested.diskGb() <= diskGb;
    }

    public double score(VmPlacementResources allocated) {
        double freeMemoryRatio = Math.max(0, 1.0 - (double) allocated.memoryMb() / memoryMb);
        double cpuAllocationRatio = (double) allocated.cpuThreads() / cpuThreads;
        return 0.6 * freeMemoryRatio + 0.4 * (1.0 - cpuAllocationRatio);
    }

    public boolean reservationAware() {
        return reservationAware;
    }

    private static boolean fitsDimension(long limit, long allocated, long requested) {
        return requested <= limit && allocated <= limit - requested;
    }

    public record VmPlacementResources(long cpuThreads, long memoryMb, long diskGb) {
    }
}
