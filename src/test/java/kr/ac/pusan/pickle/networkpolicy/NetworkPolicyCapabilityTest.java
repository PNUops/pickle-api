package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class NetworkPolicyCapabilityTest {

    @Test
    void anUnconfiguredDeploymentRemainsDisabledEvenIfAnAgentSupportsAcl() {
        var properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("pickle.network-policy", Bindable.of(NetworkPolicyProperties.class));
        assertThat(properties.enabled()).isFalse();
        var capability = new NetworkPolicyCapability(properties);
        assertThat(capability.sourceAclAvailable(Set.of(NetworkPolicyCapability.SOURCE_ACL))).isFalse();
        assertThatThrownBy(() -> capability.requireVmBackend(true, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enablingTheFeatureDoesNotInventAnAgentsSupportOrNodeHealth() {
        var capability = new NetworkPolicyCapability(new NetworkPolicyProperties(true, List.of()));
        assertThat(capability.sourceAclAvailable(null)).isFalse();
        assertThat(capability.sourceAclAvailable(Set.of())).isFalse();
        assertThat(capability.sourceAclAvailable(Set.of(NetworkPolicyCapability.SOURCE_ACL))).isTrue();
        assertThatThrownBy(() -> capability.requireVmBackend(true, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> capability.requireVmBackend(false, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void campusPresetHasNoInferredOrBroadDefault() {
        var empty = new NetworkPolicyProperties(true, null);
        assertThatThrownBy(() -> empty.campusPreset(true)).isInstanceOf(IllegalStateException.class);
        var configured = new NetworkPolicyProperties(true, List.of("192.0.2.0/24", "2001:DB8::/32"));
        assertThat(configured.campusPreset(true).cidrValues()).containsExactly("192.0.2.0/24", "2001:db8::/32");
        assertThatThrownBy(() -> configured.campusPreset(false)).isInstanceOf(IllegalArgumentException.class);
    }
}
