package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record GpuAttachmentOption(UUID vmId, String vmName, UUID nodeId, boolean ready, @Nullable String reason) {}
