package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import kr.ac.pusan.pickle.gpu.GpuConnectionStatus;

/** VM viewers can identify connected hardware; only GPU grantees get its detail link. */
public record GpuVmSummary(UUID allocationId, String allocationName, String model,
        GpuConnectionStatus connectionStatus, boolean detailAccessAllowed) {}
