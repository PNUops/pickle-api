package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.DROP;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.IN;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.ANY;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.TCP;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import org.junit.jupiter.api.Test;

class VmNetworkPolicyCompilerTest {

    @Test
    void defaultPolicyDoesNotGrantTheRestOfTheGuestsSubnet() {
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", properties(),
                List.of(), List.of());
        assertThat(plan.options()).containsEntry("policy_in", "DROP")
                .containsEntry("policy_out", "ACCEPT")
                .containsEntry("ipfilter", "1").containsEntry("macfilter", "1");
        assertThat(plan.ipFilterNet0()).containsExactly("192.0.2.20/32");
        assertThat(plan.controlRules().subList(0, 2)).allSatisfy(rule -> {
            assertThat(rule).containsEntry("dport", "22").containsEntry("action", "ACCEPT");
            assertThat(rule.get("source")).endsWith("/32");
        });
        assertThat(plan.controlRules().get(2)).containsEntry("action", "DROP")
                .containsEntry("source", "::/0").doesNotContainKey("ipversion");
        assertThat(plan.controlRules().get(3)).containsEntry("action", "DROP")
                .containsEntry("dest", "::/0").doesNotContainKey("ipversion");
        assertThat(plan.options()).containsEntry("ndp", "0");
        assertThat(plan.mutableRules()).isEmpty();
    }

    @Test
    void duplicateSshSourcesCompileToOneControlRule() {
        var properties = new VmFirewallPolicyProperties(true, "pickle-guard",
                List.of("198.51.100.10"), List.of("198.51.100.10"),
                List.of("198.51.100.30"), List.of("198.51.100.40"));

        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", properties,
                List.of(), List.of());

        assertThat(plan.controlRules().getFirst()).containsEntry("source", "198.51.100.10/32");
        assertThat(plan.controlRules()).hasSize(3);
    }

    @Test
    void userDropAllIsStagedSeparatelyFromRequiredControlPaths() {
        var dropAll = new VmNetworkRule(IN, DROP, ANY, CidrBlock.parse("0.0.0.0/0"), null, null);
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", properties(),
                List.of(), List.of(dropAll));
        assertThat(plan.controlRules()).hasSize(4);
        assertThat(plan.mutableRules()).singleElement()
                .satisfies(rule -> assertThat(rule).containsEntry("action", "DROP"));
    }

    @Test
    void publishingAddsOnlyTheActualTrustedSourceAndPort() {
        var path = new VmNetworkPolicyCompiler.PublishedPath("203.0.113.7", TCP, 8080);
        var plan = VmNetworkPolicyCompiler.compile("192.0.2.20", properties(),
                List.of(path), List.of());
        assertThat(plan.mutableRules().getFirst()).containsEntry("source", "203.0.113.7/32")
                .containsEntry("dport", "8080").containsEntry("proto", "tcp");
    }

    private static VmFirewallPolicyProperties properties() {
        return new VmFirewallPolicyProperties(true, "pickle-guard",
                List.of("198.51.100.10"), List.of("198.51.100.20"),
                List.of("198.51.100.30"), List.of("198.51.100.40"));
    }
}
