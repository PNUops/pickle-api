package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.vm.Vm;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code IpAllocationView}: one allocation row with its pool
 * and VM context. VM fields are defensively nullable per contract (VM rows are
 * permanent, so RELEASED history rows normally still resolve them).
 */
public record IpAllocationResponse(
        Long id,
        Long poolId,
        String poolName,
        String ip,
        @Nullable Long vmId,
        @Nullable String vmName,
        @Nullable String hostname,
        AllocationStatus status,
        Instant allocatedAt,
        @Nullable Instant releasedAt) {

    public static IpAllocationResponse from(IpAllocation allocation, String poolName, Vm vm) {
        return new IpAllocationResponse(allocation.getId(), allocation.getPoolId(), poolName,
                allocation.getIp(), allocation.getVmId(),
                vm == null ? null : vm.getName(),
                vm == null ? null : vm.getHostname(),
                allocation.getStatus(), allocation.getAllocatedAt(), allocation.getReleasedAt());
    }
}
