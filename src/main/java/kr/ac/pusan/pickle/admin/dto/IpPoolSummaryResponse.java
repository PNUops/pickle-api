package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.ipam.IpamService;
import java.util.UUID;

/** Contract schema {@code IpPoolSummary} — pool occupancy on the node view. */
public record IpPoolSummaryResponse(
        UUID id,
        String name,
        String cidr,
        long allocatedCount,
        long freeCount) {

    public static IpPoolSummaryResponse from(IpamService.PoolUsage usage) {
        return new IpPoolSummaryResponse(usage.pool().getPublicId(), usage.pool().getName(),
                usage.pool().getCidr(), usage.allocatedCount(), usage.freeCount());
    }
}
