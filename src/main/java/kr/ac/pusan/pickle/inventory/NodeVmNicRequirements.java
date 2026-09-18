package kr.ac.pusan.pickle.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit requirements registered for a prepared node; absence retains legacy behavior. */
public record NodeVmNicRequirements(int mtu, boolean firewall) {

    public NodeVmNicRequirements {
        if (mtu < 1280 || mtu > 1500 || !firewall) {
            throw invalid();
        }
    }

    public static Optional<NodeVmNicRequirements> read(Map<String, ?> labels) {
        if (labels == null || !labels.containsKey("vm_nic_requirements")) {
            return Optional.empty();
        }
        Object value = labels.get("vm_nic_requirements");
        if (!(value instanceof Map<?, ?> document)
                || !document.keySet().equals(Set.of("schema_version", "mtu", "firewall"))
                || integer(document.get("schema_version")) != 1
                || !Boolean.TRUE.equals(document.get("firewall"))) {
            throw invalid();
        }
        return Optional.of(new NodeVmNicRequirements(integer(document.get("mtu")), true));
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw invalid();
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw invalid();
        }
        return (int) number;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("노드의 VM 네트워크 준비 정보를 확인할 수 없습니다.");
    }
}
