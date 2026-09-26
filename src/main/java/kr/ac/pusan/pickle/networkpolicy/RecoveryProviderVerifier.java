package kr.ac.pusan.pickle.networkpolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;

/** Read-only source and target proof around a recovery policy write. */
public class RecoveryProviderVerifier {

    private final ProxmoxClient proxmox;

    public RecoveryProviderVerifier(ProxmoxClient proxmox) {
        this.proxmox = proxmox;
    }

    public void verify(RecoveryPolicyEvidence evidence, String sourceHost, String targetHost,
            String sourceBridge, String targetBridge, int sourceMtu, int targetMtu) {
        verifyOne(evidence, evidence.source(), sourceHost, sourceBridge, sourceMtu,
                "pickle-recovery-owner:" + evidence.operationId() + ":" + evidence.fencingToken());
        verifyOne(evidence, evidence.target(), targetHost, targetBridge, targetMtu,
                "pickle-recovery-operation:" + evidence.operationId());
    }

    private void verifyOne(RecoveryPolicyEvidence evidence, RecoveryPolicyEvidence.Place place,
            String apiHost, String bridge, int mtu, String marker) {
        if (!evidence.identity().bridge().equals(bridge) || mtu != 1370) {
            throw invalid();
        }
        List<Map<String, Object>> tasks = proxmox.activeNodeTasks(apiHost, place.name());
        List<Map<String, Object>> ha = proxmox.clusterHaResources(apiHost);
        if (tasks == null || !tasks.isEmpty() || ha == null || ha.stream().anyMatch(row ->
                ("vm:" + place.vmid()).equals(text(row.get("sid")))
                        || ("vm:" + place.vmid()).equals(text(row.get("id"))))) {
            throw invalid();
        }
        Map<String, Object> status = proxmox.currentVmStatus(apiHost, place.name(), place.vmid());
        Map<String, Object> config = proxmox.currentVmConfig(apiHost, place.name(), place.vmid());
        List<Map<String, Object>> pending = proxmox.pendingVmConfig(
                apiHost, place.name(), place.vmid());
        if (status == null || config == null || pending == null
                || !"stopped".equals(text(status.get("status")))
                || !"0".equals(text(config.getOrDefault("onboot", 0)))
                || !place.providerDigest().equals(text(config.get("digest")).toLowerCase())
                || !evidence.identity().hostname().equals(text(config.get("name")))
                || !evidence.identity().ipconfig0().equals(text(config.get("ipconfig0")))
                || !hasDescriptionLine(config.get("description"), marker)
                || !hasManagedTag(config.get("tags"))
                || config.containsKey("lock") || config.containsKey("args")
                || config.keySet().stream().anyMatch(key -> key.matches("hostpci[0-9]+"))
                || pending.stream().anyMatch(row -> row.containsKey("pending")
                        || truthy(row.get("delete")))) {
            throw invalid();
        }
        Set<String> nics = config.keySet().stream().filter(key -> key.matches("net[0-9]+"))
                .collect(java.util.stream.Collectors.toSet());
        if (!nics.equals(Set.of("net0"))) {
            throw invalid();
        }
        Map<String, String> nic = commaFields(config.get("net0"));
        if (!evidence.identity().mac().equalsIgnoreCase(nic.get("virtio"))
                || !bridge.equals(nic.get("bridge")) || !"1".equals(nic.get("firewall"))
                || !String.valueOf(mtu).equals(nic.get("mtu"))
                || !"1".equals(nic.get("link_down"))
                || nic.containsKey("tag") || nic.containsKey("trunks")) {
            throw invalid();
        }
        Map<String, String> ipconfig = commaFields(config.get("ipconfig0"));
        if (!evidence.identity().ip().equals(ipconfig.getOrDefault("ip", "").split("/")[0])
                || ipconfig.get("gw") == null) {
            throw invalid();
        }
        // The PVE digest pins the complete current config, including disk fields.
        // The manifest's JSON SHA is a different serializer and is bound by the proof/audit.
    }

    private static Map<String, String> commaFields(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw invalid();
        }
        Map<String, String> fields = new HashMap<>();
        for (String part : value.split(",", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                    || fields.putIfAbsent(pair[0], pair[1]) != null) {
                throw invalid();
            }
        }
        return fields;
    }

    private static boolean hasDescriptionLine(Object raw, String marker) {
        return text(raw).lines().filter(marker::equals).count() == 1;
    }

    private static boolean hasManagedTag(Object raw) {
        return java.util.Arrays.stream(text(raw).split("[;,]"))
                .anyMatch(tag -> "pickle".equalsIgnoreCase(tag.strip()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number ? number.intValue() != 0
                : value != null && !"0".equals(text(value)) && !"false".equalsIgnoreCase(text(value));
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Recovery provider identity or isolation differs from proof.");
    }
}
