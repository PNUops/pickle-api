package kr.ac.pusan.pickle.networkpolicy.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyApplyState;
import org.jspecify.annotations.Nullable;

/** Saved source policy and the separate state of its agent application. */
public record SourcePolicyView(
        long revision,
        boolean explicit,
        List<String> allowedCidrs,
        SourcePolicyApplyState applyState,
        @Nullable Long desiredGeneration,
        @Nullable Long appliedGeneration,
        @Nullable String lastError,
        @Nullable Instant updatedAt) {
}
