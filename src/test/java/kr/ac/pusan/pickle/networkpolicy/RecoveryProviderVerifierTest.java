package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryProviderVerifierTest {

    @TempDir Path temporary;

    @Test
    void rejectsRunningGuestWrongMacAndPendingConfigBeforePolicyWrite() throws Exception {
        RecoveryPolicyEvidence proof = RecoveryEvidenceFixtures.create(temporary).evidence();
        ProxmoxClient proxmox = mock(ProxmoxClient.class);
        RecoveryProviderVerifier verifier = new RecoveryProviderVerifier(proxmox);
        when(proxmox.activeNodeTasks(anyString(), anyString())).thenReturn(List.of());
        when(proxmox.clusterHaResources(anyString())).thenReturn(List.of());
        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "stopped"));
        when(proxmox.pendingVmConfig(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(Map.of("key", "net0", "current", "stable")));
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> config(proof, call.getArgument(2), proof.identity().mac()));
        verify(verifier, proof);

        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "running"));
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "stopped"));
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> config(proof, call.getArgument(2), "02:00:00:00:00:99"));
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> config(proof, call.getArgument(2), proof.identity().mac()));
        when(proxmox.pendingVmConfig(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(Map.of("key", "net0", "pending", "changed")));
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
        Map<String, Object> nullPending = new HashMap<>();
        nullPending.put("pending", null);
        when(proxmox.pendingVmConfig(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(nullPending));
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsChangedDiskDigestAndHaOwner() throws Exception {
        RecoveryPolicyEvidence proof = RecoveryEvidenceFixtures.create(temporary).evidence();
        ProxmoxClient proxmox = mock(ProxmoxClient.class);
        RecoveryProviderVerifier verifier = new RecoveryProviderVerifier(proxmox);
        when(proxmox.activeNodeTasks(anyString(), anyString())).thenReturn(List.of());
        when(proxmox.clusterHaResources(anyString())).thenReturn(List.of());
        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("status", "stopped"));
        when(proxmox.pendingVmConfig(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> {
                    Map<String, Object> config = config(proof, call.getArgument(2),
                            proof.identity().mac());
                    config.put("digest", "c".repeat(40));
                    return config;
                });
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> config(proof, call.getArgument(2), proof.identity().mac()));
        when(proxmox.clusterHaResources(anyString()))
                .thenReturn(List.of(Map.of("sid", "vm:" + proof.source().vmid())));
        assertThatThrownBy(() -> verify(verifier, proof)).isInstanceOf(IllegalStateException.class);
    }

    private static void verify(RecoveryProviderVerifier verifier, RecoveryPolicyEvidence proof) {
        verifier.verify(proof, "https://source.example.test:8006", "https://target.example.test:8006",
                proof.identity().bridge(), proof.identity().bridge(), 1370, 1370);
    }

    private static Map<String, Object> config(RecoveryPolicyEvidence proof, int vmid, String mac) {
        boolean source = vmid == proof.source().vmid();
        Map<String, Object> config = new HashMap<>();
        config.put("digest", source ? proof.source().providerDigest()
                : proof.target().providerDigest());
        config.put("name", proof.identity().hostname());
        config.put("onboot", 0);
        config.put("tags", "pickle");
        config.put("net0", "virtio=" + mac + ",bridge=" + proof.identity().bridge()
                + ",firewall=1,mtu=1370,link_down=1");
        config.put("ipconfig0", proof.identity().ipconfig0());
        config.put("scsi0", "disk-example:vm-100000-disk-0");
        config.put("description", source
                ? "pickle-recovery-owner:" + proof.operationId() + ":" + proof.fencingToken()
                : "pickle-recovery-operation:" + proof.operationId());
        return config;
    }
}
