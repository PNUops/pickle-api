package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.crypto.VmPasswordGenerator;
import kr.ac.pusan.pickle.config.SshPlatformProperties;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.ipam.IpPool;
import kr.ac.pusan.pickle.ipam.IpPoolRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyRuntimeGate;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyStore;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTimeoutException;
import kr.ac.pusan.pickle.relay.PortMappingTeardownService;
import kr.ac.pusan.pickle.sshkey.VmSshKeyRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class ProvisionVmNetworkPolicyTest {

    @Test
    void preparedVmStaysOnbootOffUntilInitialPolicyCompletes() {
        VmRepository vms = mock(VmRepository.class);
        NodeRepository nodes = mock(NodeRepository.class);
        IpAllocationRepository allocations = mock(IpAllocationRepository.class);
        IpPoolRepository pools = mock(IpPoolRepository.class);
        ProxmoxClient proxmox = mock(ProxmoxClient.class);
        VmNetworkPolicyRuntimeGate gate = mock(VmNetworkPolicyRuntimeGate.class);
        VmPasswordGenerator passwords = mock(VmPasswordGenerator.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        SshPlatformProperties ssh = mock(SshPlatformProperties.class);
        Vm vm = mock(Vm.class);
        Node node = mock(Node.class);
        IpAllocation allocation = mock(IpAllocation.class);
        IpPool pool = mock(IpPool.class);

        when(vm.getId()).thenReturn(11L);
        when(vm.getNodeId()).thenReturn(5L);
        when(vm.getIpAllocationId()).thenReturn(7L);
        when(vm.getProxmoxVmid()).thenReturn(101);
        when(vm.getVcpu()).thenReturn(2);
        when(vm.getMemoryMb()).thenReturn(2048);
        when(vm.getSshUsername()).thenReturn("debian");
        when(nodes.findById(5L)).thenReturn(Optional.of(node));
        when(node.getApiHost()).thenReturn("pve.example.test");
        when(node.getName()).thenReturn("pve-example");
        when(node.getVmBridge()).thenReturn("pguest");
        when(allocations.findById(7L)).thenReturn(Optional.of(allocation));
        when(allocation.getPoolId()).thenReturn(9L);
        when(allocation.getIp()).thenReturn("192.0.2.20");
        when(pools.findById(9L)).thenReturn(Optional.of(pool));
        when(pool.getCidr()).thenReturn("192.0.2.0/24");
        when(pool.getGateway()).thenReturn("192.0.2.1");
        when(pool.getDns()).thenReturn("[]");
        when(passwords.generate()).thenReturn("generated-password");
        when(cipher.encrypt(any())).thenReturn("encrypted");
        when(encoder.encode(any())).thenReturn("hashed");
        when(ssh.requirePlatformPublicKey()).thenReturn("ssh-ed25519 AAAATest");
        when(ssh.hasTerminalPublicKey()).thenReturn(false);
        when(gate.required(11L, node)).thenReturn(true);
        when(proxmox.currentVmConfig("pve.example.test", "pve-example", 101))
                .thenReturn(Map.of("net0", "virtio=02:00:00:00:00:01,bridge=old"));

        ObjectMapper objectMapper = mock(ObjectMapper.class);
        JsonNode dns = mock(JsonNode.class);
        when(objectMapper.readTree("[]")).thenReturn(dns);
        when(dns.isArray()).thenReturn(false);
        ProvisionVmJob job = new ProvisionVmJob(vms, mock(VmEventRepository.class),
                mock(ProvisioningTaskRepository.class), nodes, mock(OsImageRepository.class),
                pools, allocations, mock(IpamService.class), proxmox, mock(VmidSequence.class),
                mock(JobScheduler.class), encoder, passwords, cipher,
                mock(NotificationService.class), objectMapper, ssh,
                mock(PortMappingTeardownService.class), mock(VmSshKeyRepository.class),
                mock(TransactionTemplate.class), gate);

        ReflectionTestUtils.invokeMethod(job, "configure", vm);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        var ordered = inOrder(proxmox, gate);
        ordered.verify(proxmox).config(eq("pve.example.test"), eq("pve-example"), eq(101),
                params.capture());
        ordered.verify(gate).initialize(11L);
        assertThat(params.getValue()).containsEntry("onboot", "0");

        when(proxmox.currentVmConfig("pve.example.test", "pve-example", 101))
                .thenReturn(Map.of("onboot", 0));
        when(proxmox.currentVmStatus("pve.example.test", "pve-example", 101))
                .thenReturn(Map.of("status", "stopped"));
        ReflectionTestUtils.invokeMethod(job, "keepPreparedCloneStopped", node, 101);
        verify(proxmox).currentVmStatus("pve.example.test", "pve-example", 101);

        var target = new VmNetworkPolicyStore.Target(11L, 5L, "pve.example.test",
                "pve-example", 101, "pguest", 1370, 7L, "192.0.2.20", "CREATING");
        AtomicInteger proofs = new AtomicInteger();
        when(gate.dispatchProvisionStart(eq(11L), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<VmNetworkPolicyRuntimeGate.DispatchContext, Object> action =
                    invocation.getArgument(1);
            String status = proofs.getAndIncrement() == 0 ? "stopped" : "running";
            return action.apply(new VmNetworkPolicyRuntimeGate.DispatchContext(target, status));
        });
        when(proxmox.currentVmConfig("pve.example.test", "pve-example", 101))
                .thenReturn(Map.of("onboot", 1));
        when(proxmox.start("pve.example.test", "pve-example", 101)).thenReturn("upid");
        when(proxmox.agentPing("pve.example.test", "pve-example", 101))
                .thenThrow(new ProxmoxTimeoutException("qga timeout"))
                .thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ReflectionTestUtils.invokeMethod(job, "start", vm))
                .isInstanceOf(ProxmoxTimeoutException.class);
        ReflectionTestUtils.invokeMethod(job, "start", vm);

        verify(gate, times(2)).dispatchProvisionStart(eq(11L), any());
        verify(proxmox, times(1)).start("pve.example.test", "pve-example", 101);
    }
}
