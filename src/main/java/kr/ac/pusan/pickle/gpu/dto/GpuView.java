package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import kr.ac.pusan.pickle.gpu.GpuStatus;

public record GpuView(UUID id, UUID nodeId, String model, int vramMb, GpuStatus status, boolean available) {}
