package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.ACCEPT;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.DROP;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.IN;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.OUT;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VmNetworkRuleTest {

    @Test
    void inboundRulesMatchThePeerSourceAndDestinationPort() {
        var rule = new VmNetworkRule(IN, ACCEPT, TCP, CidrBlock.parse("192.0.2.7/32"), 22, 22);
        assertThat(rule.pveFields()).containsEntry("type", "in").containsEntry("source", "192.0.2.7/32")
                .containsEntry("dport", "22").containsEntry("iface", "net0")
                .containsEntry("enable", "1").doesNotContainKey("dest");
    }

    @Test
    void outboundRulesMatchTheRemoteDestinationInsteadOfGuestSource() {
        var rule = new VmNetworkRule(OUT, DROP, UDP, CidrBlock.parse("198.51.100.0/24"), 5000, 6000);
        assertThat(rule.pveFields()).containsEntry("type", "out").containsEntry("dest", "198.51.100.0/24")
                .containsEntry("dport", "5000:6000").containsEntry("action", "DROP")
                .doesNotContainKey("source");
    }

    @Test
    void icmpRulesCarryTheCorrectAddressFamilyAndNoPorts() {
        var rule = new VmNetworkRule(IN, ACCEPT, ICMPV6, CidrBlock.parse("2001:db8::/32"), null, null);
        assertThat(rule.pveFields()).containsEntry("proto", "ipv6-icmp").doesNotContainKey("dport");
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, ICMP,
                CidrBlock.parse("::/0"), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedPortConstraintsNeverReachTheHypervisor() {
        var peer = CidrBlock.parse("192.0.2.0/24");
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, TCP, peer, 22, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, UDP, peer, 0, 53))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, TCP, peer, 100, 99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, TCP, peer, 1, 65536))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VmNetworkRule(IN, ACCEPT, ANY, peer, 22, 22))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
