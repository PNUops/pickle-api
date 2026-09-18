package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeVmNicRequirementsTest {

    @Test
    void absentLabelsKeepLegacyBehaviorButPresentMalformedLabelsNeverFallBack() {
        assertThat(NodeVmNicRequirements.read(null)).isEmpty();
        assertThat(NodeVmNicRequirements.read(Map.of("gpu", true))).isEmpty();
        Map<String, Object> labels = new HashMap<>();
        labels.put("vm_nic_requirements", null);
        assertThatThrownBy(() -> NodeVmNicRequirements.read(labels)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsOnlyTheExactVersionedRequirements() {
        Map<String, Object> requirement = new HashMap<>(Map.of("schema_version", 1, "mtu", 1370L, "firewall", true));
        assertThat(NodeVmNicRequirements.read(Map.of("vm_nic_requirements", requirement)))
                .contains(new NodeVmNicRequirements(1370, true));
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of("schema_version", 2, "mtu", 1370.0, "firewall", false).entrySet()) {
            Map<String, Object> changed = new HashMap<>(requirement);
            changed.put(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> NodeVmNicRequirements.read(Map.of("vm_nic_requirements", changed)))
                    .isInstanceOf(IllegalStateException.class);
        }
        requirement.put("unexpected", true);
        assertThatThrownBy(() -> NodeVmNicRequirements.read(Map.of("vm_nic_requirements", requirement)))
                .isInstanceOf(IllegalStateException.class);
    }
}
