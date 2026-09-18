package kr.ac.pusan.pickle.provisioning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.inventory.NodeVmNicRequirements;

/** Retargets an existing PVE NIC without losing its MAC, MTU or firewall configuration. */
public final class VmNicConfiguration {

    private static final Set<String> MODELS = Set.of("virtio", "e1000", "e1000e", "rtl8139",
            "vmxnet3", "ne2k_pci", "pcnet", "i82551", "i82557b", "i82559er");

    private VmNicConfiguration() {
    }

    public static String onBridge(String existing, String bridge, Optional<NodeVmNicRequirements> requirements) {
        if (bridge == null || !bridge.matches("[A-Za-z][A-Za-z0-9_.-]{0,14}")) {
            throw invalid();
        }
        Map<String, String> fields = parse(existing);
        requirements.ifPresent(value -> requirePrepared(fields, value));
        fields.put("bridge", bridge);
        return fields.entrySet().stream()
                .map(entry -> entry.getValue() == null ? entry.getKey() : entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    public static void requirePrepared(String existing, NodeVmNicRequirements requirements) {
        requirePrepared(parse(existing), requirements);
    }

    private static void requirePrepared(Map<String, String> fields, NodeVmNicRequirements requirements) {
        if (!Integer.toString(requirements.mtu()).equals(fields.get("mtu"))
                || !"1".equals(fields.get("firewall"))) {
            throw new IllegalStateException("VM 네트워크의 MTU 또는 방화벽 준비 값이 노드 기준과 다릅니다.");
        }
    }

    private static Map<String, String> parse(String existing) {
        Map<String, String> result = new LinkedHashMap<>();
        if (existing == null || existing.isBlank()) {
            result.put("virtio", null);
            return result;
        }
        if (existing.chars().anyMatch(Character::isWhitespace)
                || existing.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        boolean modelSeen = false;
        for (String field : existing.split(",", -1)) {
            String[] pair = field.split("=", -1);
            if (pair.length > 2 || pair[0].isEmpty() || result.containsKey(pair[0])
                    || (pair.length == 2 && pair[1].isEmpty())
                    || !pair[0].matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw invalid();
            }
            boolean model = MODELS.contains(pair[0]);
            if ((pair.length == 1 && !model) || (model && modelSeen)) {
                throw invalid();
            }
            modelSeen |= model;
            result.put(pair[0], pair.length == 1 ? null : pair[1]);
        }
        if (!modelSeen) {
            throw invalid();
        }
        return result;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("VM 네트워크 설정을 안전하게 보존할 수 없습니다.");
    }
}
