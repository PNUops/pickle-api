package kr.ac.pusan.pickle.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Durable node opt-in for VM firewall policy enforcement. */
public record NodeVmFirewallPolicy(int schemaVersion) {

    public NodeVmFirewallPolicy {
        if (schemaVersion != 1) {
            throw invalid();
        }
    }

    public static Optional<NodeVmFirewallPolicy> read(Map<String, ?> labels) {
        if (labels == null || !labels.containsKey("vm_firewall_policy")) {
            return Optional.empty();
        }
        Object value = labels.get("vm_firewall_policy");
        if (!(value instanceof Map<?, ?> document)
                || !document.keySet().equals(Set.of("schema_version"))) {
            throw invalid();
        }
        Object raw = document.get("schema_version");
        if (!(raw instanceof Integer) && !(raw instanceof Long)) {
            throw invalid();
        }
        return Optional.of(new NodeVmFirewallPolicy(((Number) raw).intValue()));
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("노드의 VM 방화벽 정책 준비 정보를 확인할 수 없습니다.");
    }
}
