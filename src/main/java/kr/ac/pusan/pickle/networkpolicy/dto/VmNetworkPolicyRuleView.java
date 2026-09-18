package kr.ac.pusan.pickle.networkpolicy.dto;

import kr.ac.pusan.pickle.networkpolicy.VmNetworkRule;
import org.jspecify.annotations.Nullable;

/** One ordered IPv4 VM rule. */
public record VmNetworkPolicyRuleView(
        VmNetworkRule.Direction direction,
        VmNetworkRule.Action action,
        VmNetworkRule.Protocol protocol,
        String peer,
        @Nullable Integer portStart,
        @Nullable Integer portEnd) {
}
