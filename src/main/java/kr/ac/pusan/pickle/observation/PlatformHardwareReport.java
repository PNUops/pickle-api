package kr.ac.pusan.pickle.observation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Closed ingestion shape; not a public API schema or an arbitrary JSON archive. */
record PlatformHardwareReport(int schemaVersion, UUID sourceId, Instant observedAt,
        List<PlatformHardwareAssetReport> assets) {
    record PlatformHardwareAssetReport(String id, String displayModel, String cpuModel,
            int cores, int threads, long memoryMiB, List<PlatformHardwareGpuReport> gpus) { }
    record PlatformHardwareGpuReport(String model, @Nullable Integer count,
            @Nullable Long dedicatedMemoryMiB, @Nullable Long unifiedMemoryMiB) { }
}
