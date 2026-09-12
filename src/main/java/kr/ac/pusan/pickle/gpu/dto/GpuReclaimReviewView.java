package kr.ac.pusan.pickle.gpu.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.gpu.GpuReviewDecision;

public record GpuReclaimReviewView(UUID id, UUID allocationId, String allocationName,
        String reason, Map<String, Object> evidence, @Nullable GpuReviewDecision decision,
        @Nullable String decisionReason, Instant createdAt, @Nullable Instant decidedAt) {}
