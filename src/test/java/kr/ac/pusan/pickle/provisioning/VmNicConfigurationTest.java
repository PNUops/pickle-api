package kr.ac.pusan.pickle.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import kr.ac.pusan.pickle.inventory.NodeVmNicRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VmNicConfigurationTest {

    @Test
    void replacingBridgePreservesEveryOtherPropertyAndItsOrder() {
        String existing = "virtio=02:00:00:00:00:01,bridge=oldnet,firewall=1,mtu=1370,queues=4,rate=10,tag=9,trunks=10;11,link_down=1";
        assertThat(VmNicConfiguration.onBridge(existing, "newnet", Optional.of(new NodeVmNicRequirements(1370, true))))
                .isEqualTo(existing.replace("bridge=oldnet", "bridge=newnet"));
    }

    @Test
    void anUnconfiguredLegacyNodeDoesNotEnableOrReplacePolicyOptions() {
        assertThat(VmNicConfiguration.onBridge("virtio=02:00:00:00:00:02,firewall=0,mtu=1500", "vmbr2", Optional.empty()))
                .isEqualTo("virtio=02:00:00:00:00:02,firewall=0,mtu=1500,bridge=vmbr2");
        assertThat(VmNicConfiguration.onBridge(null, "vmbr2", Optional.empty())).isEqualTo("virtio,bridge=vmbr2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"virtio", "virtio,mtu=1370", "virtio,firewall=1", "virtio,firewall=0,mtu=1370", "virtio,firewall=1,mtu=1500", "virtio,firewall=1,mtu=0"})
    void aPreparedNodeRejectsMissingOrDriftedTemplateRequirements(String existing) {
        assertThatThrownBy(() -> VmNicConfiguration.onBridge(existing, "newnet",
                Optional.of(new NodeVmNicRequirements(1370, true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"virtio,mtu=1370,mtu=1500", "virtio,,bridge=vmbr2", "virtio,bridge=", "virtio,bridge=x\n", "bridge=vmbr2", "virtio,e1000", "virtio,firewall", "virtio,bridge=x=y"})
    void rejectsAmbiguousOrUnparseableConfigurations(String existing) {
        assertThatThrownBy(() -> VmNicConfiguration.onBridge(existing, "newnet", Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad,firewall=0", "has space", "newnet\n", "more-than-fifteen-characters"})
    void bridgeCannotInjectOtherProperties(String bridge) {
        assertThatThrownBy(() -> VmNicConfiguration.onBridge("virtio", bridge, Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }
}
