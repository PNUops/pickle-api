package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.gpu.GpuAllocationStatus;
import kr.ac.pusan.pickle.gpu.GpuConnectionStatus;
import kr.ac.pusan.pickle.gpu.GpuReleaseReason;

public record GpuAllocationView(UUID id, String name, GpuAllocationStatus status,
        GpuConnectionStatus connectionStatus, UUID workspaceId, String workspaceName,
        @Nullable UUID orgId, @Nullable String orgName, boolean accessLimited,
        List<String> ownerNames, boolean accessManageAllowed, @Nullable ResourceRole myRole,
        @Nullable GpuView gpu, @Nullable UUID vmId, @Nullable String vmName,
        @Nullable Integer grantedLeaseHours, @Nullable Instant allocatedAt,
        @Nullable Instant leaseEndsAt, @Nullable Instant unattachedSince,
        @Nullable Long queuePosition, @Nullable Integer priority,
        @Nullable Double utilizationPercent, @Nullable Instant sampleObservedAt,
        @Nullable GpuReleaseReason releaseReason, @Nullable String error,
        Instant createdAt, Instant updatedAt) {}
