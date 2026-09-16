package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.DROP;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.IN;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.ANY;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.TCP;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VmNetworkPolicyCompilerTest {

    @Test
    void defaultPolicyDoesNotGrantTheRestOfTheGuestsSubnet() {
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", "198.51.100.10",
                "198.51.100.20", List.of(), List.of());
        assertThat(plan.options()).containsEntry("policy_in", "DROP").containsEntry("policy_out", "ACCEPT")
                .containsEntry("ipfilter", "1").containsEntry("macfilter", "1");
        assertThat(plan.ipFilterNet0()).containsExactly("192.0.2.20/32");
        assertThat(plan.rules()).hasSize(2).allSatisfy(rule -> {
            assertThat(rule).containsEntry("dport", "22").containsEntry("action", "ACCEPT");
            assertThat(rule.get("source")).endsWith("/32");
        });
    }

    @Test
    void userDropAllCannotOverrideRequiredGatewayAndTerminalPaths() {
        var dropAll = new VmNetworkRule(IN, DROP, ANY, CidrBlock.parse("0.0.0.0/0"), null, null);
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", "198.51.100.10",
                "198.51.100.20", List.of(), List.of(dropAll));
        assertThat(plan.rules()).hasSize(3);
        assertThat(plan.rules().get(0)).containsEntry("action", "ACCEPT").containsEntry("dport", "22");
        assertThat(plan.rules().get(1)).containsEntry("action", "ACCEPT").containsEntry("dport", "22");
        assertThat(plan.rules().get(2)).containsEntry("action", "DROP");
    }

    @Test
    void publishingAddsOnlyTheActualTrustedSourceAndPort() {
        var path = new VmNetworkPolicyCompiler.PublishedPath("203.0.113.7", TCP, 8080);
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", "198.51.100.10",
                "198.51.100.20", List.of(path), List.of());
        assertThat(plan.rules().get(2)).containsEntry("source", "203.0.113.7/32")
                .containsEntry("dport", "8080").containsEntry("proto", "tcp");
    }
}
