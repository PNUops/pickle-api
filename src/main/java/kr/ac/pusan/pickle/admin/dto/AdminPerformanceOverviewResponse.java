package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Global inventory facts; missing or stale automatic reports are not zero capacity. */
public record AdminPerformanceOverviewResponse(
        @Nullable PerformanceOverviewHardware hardware,
        @Nullable PerformanceOverviewBusiness business) {

    public record PerformanceOverviewHardware(long physicalHosts, long cpuCores, long cpuThreads,
            long memoryMiB, long gpuDevices, @Nullable Long dedicatedGpuMemoryMiB,
            List<PerformanceOverviewAsset> assets) {
    }

    public record PerformanceOverviewAsset(String displayModel, String cpuModel, int cpuCores,
            int cpuThreads, long memoryMiB, List<PerformanceOverviewGpu> gpus) {
    }

    public record PerformanceOverviewGpu(String model, int count,
            @Nullable Long dedicatedMemoryMiB, @Nullable Long unifiedMemoryMiB, @Nullable Boolean sharedMemory) {
    }

    public record PerformanceOverviewBusiness(long registeredAccounts, long currentWorkspaces,
            Map<String, Long> workspacesByKind, long approvedRequests,
            Map<String, Long> approvedRequestsByKind, long vmRegistrations, long registeredDomains) {
    }
}
