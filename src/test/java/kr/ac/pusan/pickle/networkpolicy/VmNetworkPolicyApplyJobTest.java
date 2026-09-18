package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeVmFirewallPolicy;
import kr.ac.pusan.pickle.inventory.NodeVmNicRequirements;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.Test;

class VmNetworkPolicyApplyJobTest {

    @Test
    void currentFailureIsRecordedAndRetried() {
        Fixture fixture = fixture(disabledProperties());
        when(fixture.store.markFailed(eq(11L), eq(1L),
                eq(VmNetworkPolicyApplyState.FAILED), anyString()))
                .thenReturn(true);

        assertThatThrownBy(() -> fixture.job.apply(11)).isInstanceOf(IllegalStateException.class);
        verify(fixture.store).markFailed(eq(11L), eq(1L),
                eq(VmNetworkPolicyApplyState.FAILED), anyString());
    }

    @Test
    void supersededFailureDoesNotRetryAnOldGeneration() {
        Fixture fixture = fixture(disabledProperties());
        when(fixture.store.markFailed(eq(11L), eq(1L),
                eq(VmNetworkPolicyApplyState.FAILED), anyString()))
                .thenReturn(false);

        assertThatCode(() -> fixture.job.apply(11)).doesNotThrowAnyException();
    }

    @Test
    void verifiedBarrierFailureIsParkedWithoutRetry() {
        VmFirewallPolicyProperties properties = configuredProperties();
        Fixture fixture = fixture(properties);
        Vm vm = mock(Vm.class);
        Node node = mock(Node.class);
        IpAllocation allocation = mock(IpAllocation.class);
        when(vm.getNodeId()).thenReturn(5L);
        when(vm.getProxmoxVmid()).thenReturn(101);
        when(node.vmFirewallPolicy()).thenReturn(Optional.of(new NodeVmFirewallPolicy(1)));
        when(node.vmNicRequirements()).thenReturn(Optional.of(new NodeVmNicRequirements(1370, true)));
        when(node.getId()).thenReturn(5L);
        when(node.getApiHost()).thenReturn("pve.example.test");
        when(node.getName()).thenReturn("pve-example");
        when(node.getVmBridge()).thenReturn("vmbr-example");
        when(allocation.getIp()).thenReturn("192.0.2.20");
        when(fixture.vms.findById(11L)).thenReturn(Optional.of(vm));
        when(fixture.nodes.findById(5L)).thenReturn(Optional.of(node));
        when(fixture.allocations.findFirstByVmIdAndStatusOrderByIdDesc(anyLong(), any()))
                .thenReturn(Optional.of(allocation));
        when(fixture.store.target(11L, 1370)).thenReturn(new VmNetworkPolicyStore.Target(
                11L, 5L, "pve.example.test", "pve-example", 101,
                "vmbr-example", 1370, 7L, "192.0.2.20", "RUNNING"));
        doThrow(new VmFirewallBarrierReconciler.FailedClosedException("guarded", null))
                .when(fixture.reconciler).reconcile(any(), any());
        when(fixture.store.markFailedClosed(eq(11L), eq(1L), anyString(), any(),
                eq("guarded"))).thenReturn(true);

        assertThatCode(() -> fixture.job.apply(11)).doesNotThrowAnyException();
        verify(fixture.store).markFailedClosed(eq(11L), eq(1L), anyString(), any(),
                eq("guarded"));
    }

    @Test
    void changedTargetCannotPublishFailedClosed() {
        Fixture fixture = preparedFixture();
        doThrow(new VmFirewallBarrierReconciler.FailedClosedException("guarded", null))
                .when(fixture.reconciler).reconcile(any(), any());
        when(fixture.store.markFailedClosed(eq(11L), eq(1L), anyString(), any(),
                eq("guarded"))).thenReturn(false);
        when(fixture.store.markFailed(11L, 1L, VmNetworkPolicyApplyState.FAILED,
                "VM 대상 정보가 변경되어 차단 상태를 확인할 수 없습니다.")).thenReturn(true);

        assertThatCode(() -> fixture.job.apply(11L)).doesNotThrowAnyException();
        verify(fixture.store).markFailed(11L, 1L, VmNetworkPolicyApplyState.FAILED,
                "VM 대상 정보가 변경되어 차단 상태를 확인할 수 없습니다.");
    }

    @Test
    void rawProviderFailureIsNotStoredInUserVisibleLastError() {
        Fixture fixture = preparedFixture();
        doThrow(new RuntimeException(
                "GET https://10.0.0.8/api2/json token=do-not-store"))
                .when(fixture.reconciler).reconcile(any(), any());
        when(fixture.store.markFailed(11L, 1L, VmNetworkPolicyApplyState.FAILED,
                "Proxmox VM 방화벽 설정 상태를 확인할 수 없습니다.")).thenReturn(true);

        assertThatThrownBy(() -> fixture.job.apply(11L)).isInstanceOf(RuntimeException.class);
        verify(fixture.store).markFailed(11L, 1L, VmNetworkPolicyApplyState.FAILED,
                "Proxmox VM 방화벽 설정 상태를 확인할 수 없습니다.");
    }

    private static Fixture preparedFixture() {
        VmFirewallPolicyProperties properties = configuredProperties();
        Fixture fixture = fixture(properties);
        Vm vm = mock(Vm.class);
        Node node = mock(Node.class);
        IpAllocation allocation = mock(IpAllocation.class);
        when(vm.getNodeId()).thenReturn(5L);
        when(vm.getProxmoxVmid()).thenReturn(101);
        when(node.getId()).thenReturn(5L);
        when(node.vmFirewallPolicy()).thenReturn(Optional.of(new NodeVmFirewallPolicy(1)));
        when(node.vmNicRequirements()).thenReturn(Optional.of(new NodeVmNicRequirements(1370, true)));
        when(node.getApiHost()).thenReturn("pve.example.test");
        when(node.getName()).thenReturn("pve-example");
        when(node.getVmBridge()).thenReturn("vmbr-example");
        when(allocation.getIp()).thenReturn("192.0.2.20");
        when(fixture.vms.findById(11L)).thenReturn(Optional.of(vm));
        when(fixture.nodes.findById(5L)).thenReturn(Optional.of(node));
        when(fixture.allocations.findFirstByVmIdAndStatusOrderByIdDesc(anyLong(), any()))
                .thenReturn(Optional.of(allocation));
        when(fixture.store.target(11L, 1370)).thenReturn(new VmNetworkPolicyStore.Target(
                11L, 5L, "pve.example.test", "pve-example", 101,
                "vmbr-example", 1370, 7L, "192.0.2.20", "RUNNING"));
        return fixture;
    }

    private static Fixture fixture(VmFirewallPolicyProperties properties) {
        VmNetworkPolicyStore store = mock(VmNetworkPolicyStore.class);
        VmNetworkPolicyAdvisoryLock lock = mock(VmNetworkPolicyAdvisoryLock.class);
        VmRepository vms = mock(VmRepository.class);
        NodeRepository nodes = mock(NodeRepository.class);
        IpAllocationRepository allocations = mock(IpAllocationRepository.class);
        VmNetworkPublishedPathStore paths = mock(VmNetworkPublishedPathStore.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        String hash = VmNetworkPolicyHashes.desired(properties, List.of(), List.of());
        var snapshot = new VmNetworkPolicyStore.Snapshot(11, 0, 1, null, hash, null,
                VmNetworkPolicyApplyState.PENDING, null, Instant.now(), List.of());
        when(store.find(11)).thenReturn(Optional.of(snapshot));
        when(paths.find(11)).thenReturn(List.of());
        when(lock.run(anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        VmNetworkPolicyApplyJob job = new VmNetworkPolicyApplyJob(store, lock, vms, nodes,
                allocations, properties, paths, reconciler);
        return new Fixture(job, store, vms, nodes, allocations, reconciler);
    }

    private static VmFirewallPolicyProperties disabledProperties() {
        return new VmFirewallPolicyProperties(false, "", List.of(), List.of(), List.of(), List.of());
    }

    private static VmFirewallPolicyProperties configuredProperties() {
        return new VmFirewallPolicyProperties(true, "pickle-guard",
                List.of("198.51.100.10"), List.of("198.51.100.20"),
                List.of("198.51.100.30"), List.of("198.51.100.40"));
    }

    private record Fixture(VmNetworkPolicyApplyJob job, VmNetworkPolicyStore store,
            VmRepository vms, NodeRepository nodes, IpAllocationRepository allocations,
            VmFirewallBarrierReconciler reconciler) {
    }
}
