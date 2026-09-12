package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record GpuRequestSpecResponse(@Nullable UUID vmId, @Nullable String vmName,
        int leaseHours, @Nullable Integer grantedLeaseHours, @Nullable UUID grantedGpuId,
        @Nullable Integer grantedPriority) {}
