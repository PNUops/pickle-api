package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VmFirewallBarrierReconcilerTest {

    private static final VmFirewallBarrierReconciler.Target TARGET =
            new VmFirewallBarrierReconciler.Target(
                    "pve.example.test", "pve-example", 101, "vmbr-example", 1370);

    private ProxmoxClient proxmox;
    private VmFirewallPolicyProperties properties;
    private VmNetworkPolicyCompiler.Plan desired;

    @BeforeEach
    void setUp() {
        proxmox = mock(ProxmoxClient.class);
        properties = new VmFirewallPolicyProperties(true, "pickle-guard",
                List.of("198.51.100.10"), List.of("198.51.100.20"),
                List.of("198.51.100.30"), List.of("198.51.100.40"));
        desired = VmNetworkPolicyCompiler.compile("192.0.2.20", properties, List.of(),
                List.of(new VmNetworkRule(VmNetworkRule.Direction.IN,
                        VmNetworkRule.Action.ACCEPT, VmNetworkRule.Protocol.TCP,
                        CidrBlock.parse("203.0.113.0/24"), 443, 443)));
        when(proxmox.clusterFirewallOptions(anyString())).thenReturn(Map.of("enable", 1));
        when(proxmox.nodeFirewallOptions(anyString(), anyString()))
                .thenReturn(Map.of("nftables", 0));
        when(proxmox.clusterFirewallGroups(anyString())).thenReturn(List.of(Map.of(
                "group", "pickle-guard", "comment", VmFirewallPolicyProperties.BARRIER_COMMENT)));
        when(proxmox.clusterFirewallGroupRules(anyString(), anyString())).thenReturn(List.of(
                Map.of("type", "in", "action", "DROP", "enable", 1),
                Map.of("type", "out", "action", "DROP", "enable", 1)));
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("net0", "virtio=02:00:00:00:00:01,bridge=vmbr-example,"
                        + "firewall=1,mtu=1370"));
        when(proxmox.vmFirewallOptions(anyString(), anyString(), anyInt()))
                .thenReturn(optionsWithDigest(desired.options()));
        when(proxmox.vmFirewallIpSets(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(Map.of("name", "ipfilter-net0", "comment",
                        VmFirewallBarrierReconciler.IPSET_MARKER)));
        when(proxmox.vmFirewallIpSet(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(List.of(Map.of("cidr", "192.0.2.20", "comment",
                        VmFirewallBarrierReconciler.IPSET_MARKER)));
        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "stopped"));
    }

    @Test
    void replacesMutableRulesOnlyWhileTheExactBarrierIsEnabled() {
        List<Map<String, Object>> rules = mutableRules(desired.controlRules());
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("type", "in");
        old.put("action", "ACCEPT");
        old.put("enable", "1");
        old.put("iface", "net0");
        old.put("source", "192.0.2.99/32");
        old.put("dport", "80");
        old.put("proto", "tcp");
        old.put("comment", VmFirewallBarrierReconciler.MUTABLE_PREFIX + "user:old");
        rules.add(old);
        renumber(rules);
        when(proxmox.vmFirewallRules(anyString(), anyString(), anyInt()))
                .thenAnswer(ignored -> snapshot(rules));
        doAnswer(invocation -> {
            Map<String, String> fields = invocation.getArgument(3);
            if (fields.getOrDefault("comment", "").startsWith(
                    VmFirewallBarrierReconciler.MUTABLE_PREFIX)) {
                assertThat(hasEnabledBarrier(rules)).isTrue();
                assertThat(fields).containsEntry("enable", "0");
            }
            rules.addFirst(objectMap(fields));
            renumber(rules);
            return null;
        }).when(proxmox).createVmFirewallRule(anyString(), anyString(), anyInt(), any());
        doAnswer(invocation -> {
            int position = invocation.getArgument(3);
            Map<String, String> fields = invocation.getArgument(4);
            Map<String, Object> rule = rules.get(position);
            if (fields.containsKey("moveto")) {
                int destination = Integer.parseInt(fields.get("moveto"));
                List<Map<String, Object>> moved = new ArrayList<>();
                for (int index = 0; index < rules.size(); index++) {
                    if (index == destination) {
                        moved.add(rule);
                    }
                    if (index != position) {
                        moved.add(rules.get(index));
                    }
                }
                if (destination >= rules.size()) {
                    moved.add(rule);
                }
                rules.clear();
                rules.addAll(moved);
            } else {
                if (fields.getOrDefault("comment", "").startsWith(
                        VmFirewallBarrierReconciler.MUTABLE_PREFIX)) {
                    assertThat(hasEnabledBarrier(rules)).isTrue();
                }
                rule.putAll(fields);
            }
            renumber(rules);
            return null;
        }).when(proxmox).updateVmFirewallRule(anyString(), anyString(), anyInt(), anyInt(),
                any(), anyString());
        doAnswer(invocation -> {
            assertThat(hasEnabledBarrier(rules)).isTrue();
            rules.remove((int) invocation.getArgument(3));
            renumber(rules);
            return null;
        }).when(proxmox).deleteVmFirewallRule(anyString(), anyString(), anyInt(), anyInt(),
                anyString());

        new VmFirewallBarrierReconciler(proxmox, properties).reconcile(TARGET, desired);

        assertThat(rules).noneMatch(rule -> VmFirewallBarrierReconciler.BARRIER_MARKER.equals(
                rule.get("comment")));
        assertThat(rules).hasSize(desired.controlRules().size() + desired.mutableRules().size());
    }

    @Test
    void extraNicFailsWithoutChangingFirewallRules() {
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt())).thenReturn(Map.of(
                "net0", "virtio=02:00:00:00:00:01,firewall=1",
                "net1", "virtio=02:00:00:00:00:02,bridge=vmbr-example,"
                        + "firewall=1,mtu=1370"));

        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired))
                .isInstanceOf(VmFirewallBarrierReconciler.ReconcileException.class)
                .isNotInstanceOf(VmFirewallBarrierReconciler.FailedClosedException.class);
        verify(proxmox, never()).createVmFirewallRule(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void unknownEnabledRuleIsNotAdoptedOrDeleted() {
        List<Map<String, Object>> rules = mutableRules(desired.controlRules());
        rules.add(objectMap(Map.of("type", "in", "action", "ACCEPT", "enable", "1",
                "iface", "net0", "source", "203.0.113.9/32", "comment", "operator rule")));
        renumber(rules);
        when(proxmox.vmFirewallRules(anyString(), anyString(), anyInt()))
                .thenAnswer(ignored -> snapshot(rules));

        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired))
                .isInstanceOf(VmFirewallBarrierReconciler.ReconcileException.class)
                .isNotInstanceOf(VmFirewallBarrierReconciler.FailedClosedException.class);
        verify(proxmox, never()).deleteVmFirewallRule(anyString(), anyString(), anyInt(),
                anyInt(), anyString());
    }

    @Test
    void disabledClusterFirewallCannotBeReportedAsApplied() {
        when(proxmox.clusterFirewallOptions(anyString())).thenReturn(Map.of("enable", 0));

        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired))
                .isInstanceOf(VmFirewallBarrierReconciler.ReconcileException.class)
                .isNotInstanceOf(VmFirewallBarrierReconciler.FailedClosedException.class);
        verify(proxmox, never()).createVmFirewallRule(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void constrainedBarrierReferenceIsNotAcceptedAsFailedClosed() {
        List<Map<String, Object>> rules = mutableRules(desired.controlRules());
        rules.add(objectMap(Map.of(
                "type", "group", "action", "pickle-guard", "enable", "1",
                "iface", "net0", "source", "203.0.113.0/24",
                "comment", VmFirewallBarrierReconciler.BARRIER_MARKER)));
        renumber(rules);
        when(proxmox.vmFirewallRules(anyString(), anyString(), anyInt()))
                .thenAnswer(ignored -> snapshot(rules));

        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired))
                .isInstanceOf(VmFirewallBarrierReconciler.ReconcileException.class)
                .isNotInstanceOf(VmFirewallBarrierReconciler.FailedClosedException.class);
        verify(proxmox, never()).updateVmFirewallRule(anyString(), anyString(), anyInt(),
                anyInt(), any(), anyString());
    }

    @Test
    void exactInitialStateReplaysWithoutMutation() {
        List<Map<String, Object>> rules = mutableRules(desired.controlRules());
        desired.mutableRules().forEach(rule -> rules.add(objectMap(rule)));
        renumber(rules);
        when(proxmox.vmFirewallRules(anyString(), anyString(), anyInt()))
                .thenAnswer(ignored -> snapshot(rules));

        new VmFirewallBarrierReconciler(proxmox, properties).prepareInitial(TARGET, desired);

        verify(proxmox, never()).setVmFirewallOptions(anyString(), anyString(), anyInt(),
                any(), anyString());
        verify(proxmox, never()).createVmFirewallIpSet(anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(proxmox, never()).createVmFirewallRule(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void ipFilterNomatchOrProviderErrorsCannotBeApplied() {
        when(proxmox.vmFirewallIpSet(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(List.of(Map.of("cidr", "192.0.2.20", "nomatch", 1,
                        "comment", VmFirewallBarrierReconciler.IPSET_MARKER)));
        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired)).isInstanceOf(
                        VmFirewallBarrierReconciler.ReconcileException.class);

        when(proxmox.vmFirewallIpSet(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(List.of(Map.of("cidr", "192.0.2.20", "errors",
                        Map.of("cidr", "invalid"), "comment",
                        VmFirewallBarrierReconciler.IPSET_MARKER)));
        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired)).isInstanceOf(
                        VmFirewallBarrierReconciler.ReconcileException.class);
    }

    @Test
    void vlanTaggedNet0IsRejectedWithoutMutation() {
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt())).thenReturn(Map.of(
                "net0", "virtio=02:00:00:00:00:01,bridge=vmbr-example,firewall=1,"
                        + "mtu=1370,tag=22"));
        assertThatThrownBy(() -> new VmFirewallBarrierReconciler(proxmox, properties)
                .reconcile(TARGET, desired)).isInstanceOf(
                        VmFirewallBarrierReconciler.ReconcileException.class);
        verify(proxmox, never()).createVmFirewallRule(anyString(), anyString(), anyInt(), any());
    }

    private static List<Map<String, Object>> mutableRules(List<Map<String, String>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        source.forEach(rule -> result.add(objectMap(rule)));
        return result;
    }

    private static Map<String, Object> objectMap(Map<String, String> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(source);
        return result;
    }

    private static Map<String, Object> optionsWithDigest(Map<String, String> source) {
        Map<String, Object> result = objectMap(source);
        result.put("digest", "options-digest");
        return result;
    }

    private static List<Map<String, Object>> snapshot(List<Map<String, Object>> rules) {
        return rules.stream().map(rule -> {
            Map<String, Object> copy = new LinkedHashMap<>(rule);
            String peer = String.valueOf(copy.getOrDefault("source", copy.get("dest")));
            if (!"null".equals(peer) && !peer.isBlank()) {
                copy.put("ipversion", peer.contains(":") ? 6 : 4);
            }
            return copy;
        }).toList();
    }

    private static void renumber(List<Map<String, Object>> rules) {
        for (int index = 0; index < rules.size(); index++) {
            rules.get(index).put("pos", index);
            rules.get(index).put("digest", "0123456789abcdef");
        }
    }

    private static boolean hasEnabledBarrier(List<Map<String, Object>> rules) {
        return rules.stream().anyMatch(rule -> VmFirewallBarrierReconciler.BARRIER_MARKER.equals(
                rule.get("comment")) && "pickle-guard".equals(rule.get("action"))
                && ("1".equals(rule.get("enable")) || Integer.valueOf(1).equals(rule.get("enable"))));
    }
}
