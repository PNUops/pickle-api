package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.ipam.IpamService;

/** Contract schema {@code IpPoolSummary} — pool occupancy on the node view. */
public record IpPoolSummaryResponse(
        Long id,
        String cidr,
        long allocatedCount,
        long freeCount) {

    public static IpPoolSummaryResponse from(IpamService.PoolUsage usage) {
        return new IpPoolSummaryResponse(usage.pool().getId(), usage.pool().getCidr(),
                usage.allocatedCount(), usage.freeCount());
    }
}
