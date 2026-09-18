package kr.ac.pusan.pickle.networkpolicy.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyApplyState;
import org.jspecify.annotations.Nullable;

/** Saved VM policy and its separately confirmed PVE configuration state. */
public record VmNetworkPolicyView(
        long revision,
        List<VmNetworkPolicyRuleView> rules,
        List<VmNetworkSystemRuleView> systemRules,
        VmNetworkPolicyApplyState applyState,
        @Nullable Long desiredGeneration,
        @Nullable Long appliedGeneration,
        @Nullable String lastError,
        @Nullable Instant updatedAt) {
}
