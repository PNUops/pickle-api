package kr.ac.pusan.pickle.gpu;

import java.util.UUID;

/** Private inventory identity; mapping names never leave the service API. */
public record Gpu(long id, UUID publicId, long nodeId, String mappingName,
        String model, int vramMb, String hostpciSlot, GpuStatus status) {}
