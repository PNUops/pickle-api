package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeVmFirewallPolicy;
import kr.ac.pusan.pickle.inventory.NodeVmNicRequirements;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class VmNetworkPolicyRuntimeGateTest {

    @Test
    void rowlessLegacyVmKeepsExistingPowerBehavior() {
        Fixture fixture = fixture(configured());
        when(fixture.store.find(11L)).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        String result = fixture.gate.dispatch(11L, "STOPPED", "stopped", target -> {
            calls.incrementAndGet();
            return "upid";
        });

        assertThat(result).isEqualTo("upid");
        assertThat(calls).hasValue(1);
        verify(fixture.reconciler, never()).verifyApplied(any(), any(),
                org.mockito.ArgumentMatchers.<java.util.Set<String>>any());
    }

    @Test
    void durableRowNeverBypassesWhenGlobalConfigurationDisappears() {
        Fixture fixture = fixture(disabled());
        when(fixture.store.find(11L)).thenReturn(Optional.of(snapshot(disabled())));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> fixture.gate.dispatch(11L, "STOPPED", "stopped", target -> {
            calls.incrementAndGet();
            return "upid";
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void durableRowNeverBypassesWhenNodeLabelDisappears() {
        VmFirewallPolicyProperties properties = configured();
        Fixture fixture = fixture(properties);
        VmNetworkPolicyStore.Snapshot snapshot = snapshot(properties);
        Node node = mock(Node.class);
        var target = new VmNetworkPolicyStore.Target(11L, 5L, "pve.example.test",
                "pve-example", 101, "vmbr-example", 1370, 7L, "192.0.2.20", "STOPPED");
        when(fixture.store.find(11L)).thenReturn(Optional.of(snapshot));
        when(fixture.store.target(11L, 1370)).thenReturn(target);
        when(fixture.nodes.findById(5L)).thenReturn(Optional.of(node));
        when(node.vmFirewallPolicy()).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> fixture.gate.dispatch(11L, "STOPPED", "stopped", ignored -> {
            calls.incrementAndGet();
            return "upid";
        })).isInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void finalTupleFenceRunsAfterProviderProofAndBeforeDispatch() {
        VmFirewallPolicyProperties properties = configured();
        Fixture fixture = fixture(properties);
        VmNetworkPolicyStore.Snapshot snapshot = snapshot(properties);
        prepared(fixture, snapshot);
        doAnswer(invocation -> {
            java.util.function.Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> tx =
                    (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) callback;
            tx.accept(null);
            return null;
        }).when(fixture.transactions).executeWithoutResult(any());

        String result = fixture.gate.dispatch(11L, "STOPPED", "stopped", target -> "upid");

        assertThat(result).isEqualTo("upid");
        var ordered = inOrder(fixture.reconciler, fixture.store);
        ordered.verify(fixture.reconciler).verifyApplied(any(), any(),
                org.mockito.ArgumentMatchers.<java.util.Set<String>>any());
        ordered.verify(fixture.store).requireDispatchCurrent(any(), any());
    }

    @Test
    void changedTargetAtFinalFencePreventsStart() {
        VmFirewallPolicyProperties properties = configured();
        Fixture fixture = fixture(properties);
        prepared(fixture, snapshot(properties));
        doAnswer(invocation -> {
            java.util.function.Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> tx =
                    (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) callback;
            tx.accept(null);
            return null;
        }).when(fixture.transactions).executeWithoutResult(any());
        doThrow(VmNetworkPolicyStore.unavailable("changed"))
                .when(fixture.store).requireDispatchCurrent(any(), any());
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> fixture.gate.dispatch(11L, "STOPPED", "stopped", target -> {
            calls.incrementAndGet();
            return "upid";
        })).isInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        assertThat(calls).hasValue(0);
    }

    private static void prepared(Fixture fixture, VmNetworkPolicyStore.Snapshot snapshot) {
        Node node = mock(Node.class);
        when(node.vmFirewallPolicy()).thenReturn(Optional.of(new NodeVmFirewallPolicy(1)));
        when(node.vmNicRequirements()).thenReturn(Optional.of(new NodeVmNicRequirements(1370, true)));
        when(node.getApiHost()).thenReturn("pve.example.test");
        when(node.getName()).thenReturn("pve-example");
        when(node.getVmBridge()).thenReturn("vmbr-example");
        var target = new VmNetworkPolicyStore.Target(11L, 5L, "pve.example.test",
                "pve-example", 101, "vmbr-example", 1370, 7L, "192.0.2.20", "STOPPED");
        when(fixture.store.find(11L)).thenReturn(Optional.of(snapshot));
        when(fixture.store.target(11L, 1370)).thenReturn(target);
        when(fixture.nodes.findById(5L)).thenReturn(Optional.of(node));
        when(fixture.paths.find(11L)).thenReturn(List.of());
    }

    private static VmNetworkPolicyStore.Snapshot snapshot(
            VmFirewallPolicyProperties properties) {
        String hash = VmNetworkPolicyHashes.desired(properties, List.of(), List.of());
        return new VmNetworkPolicyStore.Snapshot(11L, 0L, 1L, 1L, hash, hash,
                VmNetworkPolicyApplyState.APPLIED, null, Instant.now(), List.of());
    }

    private static Fixture fixture(VmFirewallPolicyProperties properties) {
        VmNetworkPolicyStore store = mock(VmNetworkPolicyStore.class);
        VmNetworkPolicyAdvisoryLock lock = mock(VmNetworkPolicyAdvisoryLock.class);
        NodeRepository nodes = mock(NodeRepository.class);
        VmNetworkPublishedPathStore paths = mock(VmNetworkPublishedPathStore.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(lock.run(anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        return new Fixture(new VmNetworkPolicyRuntimeGate(store, lock, nodes, properties,
                paths, reconciler, transactions), store, nodes, paths, reconciler, transactions);
    }

    private static VmFirewallPolicyProperties configured() {
        return new VmFirewallPolicyProperties(true, "pickle-guard",
                List.of("198.51.100.10"), List.of("198.51.100.20"),
                List.of("198.51.100.30"), List.of("198.51.100.40"));
    }

    private static VmFirewallPolicyProperties disabled() {
        return new VmFirewallPolicyProperties(false, "", List.of(), List.of(), List.of(), List.of());
    }

    private record Fixture(VmNetworkPolicyRuntimeGate gate, VmNetworkPolicyStore store,
            NodeRepository nodes, VmNetworkPublishedPathStore paths,
            VmFirewallBarrierReconciler reconciler, TransactionTemplate transactions) {
    }
}
