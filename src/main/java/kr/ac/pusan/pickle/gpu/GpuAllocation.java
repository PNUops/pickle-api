package kr.ac.pusan.pickle.gpu;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Allocation and attachment have independent lifetimes. */
public record GpuAllocation(long id, UUID publicId, long requestId, long workspaceId, long orgId,
        String name, Long gpuId, Long preferredGpuId, Long vmId, GpuAllocationStatus status,
        GpuConnectionStatus connectionStatus, int grantedLeaseHours, int priority,
        Instant queuedAt, LocalDate grantedStartDate, LocalDate grantedEndDate,
        Instant allocatedAt, Instant leaseEndsAt, Instant unattachedSince, Instant attachedAt,
        GpuReleaseReason releaseReason, UUID operationId, String error,
        Instant createdAt, Instant updatedAt) {
    public boolean holdsCard() {
        return status == GpuAllocationStatus.ALLOCATED || status == GpuAllocationStatus.RELEASING;
    }
}
