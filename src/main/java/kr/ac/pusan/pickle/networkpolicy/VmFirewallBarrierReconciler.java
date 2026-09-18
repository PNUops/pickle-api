package kr.ac.pusan.pickle.networkpolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import org.springframework.stereotype.Component;

/**
 * Replaces mutable VM rules behind one operator-owned IN/OUT DROP group.
 * PVE rule POST prepends, so every staged rule is disabled until moved below
 * the enabled barrier. This proves fail-closed only for new flows that reach
 * the per-VM chains; established conntrack behavior requires live acceptance.
 */
@Component
public class VmFirewallBarrierReconciler {

    static final String BARRIER_MARKER = "pickle:vmfw:v1:barrier";
    static final String MUTABLE_PREFIX = "pickle:vmfw:v1:mutable:";
    static final String CONTROL_PREFIX = "pickle:vmfw:v1:control:";
    static final String IPSET_MARKER = "pickle:vmfw:v1:ipfilter-net0";
    private static final Set<String> RULE_FIELDS = Set.of(
            "type", "action", "enable", "iface", "source", "dest", "proto",
            "dport", "sport", "comment", "icmp-type", "macro", "log");

    private final ProxmoxClient proxmox;
    private final VmFirewallPolicyProperties properties;

    public VmFirewallBarrierReconciler(ProxmoxClient proxmox,
            VmFirewallPolicyProperties properties) {
        this.proxmox = proxmox;
        this.properties = properties;
    }

    /** Creates only missing platform-owned base state while the new VM is stopped. */
    public void prepareInitial(Target target, VmNetworkPolicyCompiler.Plan desired) {
        verifyFirewallBackend(target);
        verifyBarrierGroup(target.apiHost());
        requireRuntimeStatus(target, "stopped");
        verifySupportedNic(target);
        rejectUnknownEnabled(readRules(target), 0);
        ensureOptions(target, desired);
        ensureIpFilter(target, desired);
        ensureControlPrefix(target, desired.controlRules());
        reconcile(target, desired);
        requireRuntimeStatus(target, "stopped");
    }

    /** Fresh provider proof used immediately before START/REBOOT dispatch. */
    public void verifyApplied(Target target, VmNetworkPolicyCompiler.Plan desired,
            String expectedRuntimeStatus) {
        verifyApplied(target, desired, Set.of(expectedRuntimeStatus));
    }

    public String verifyApplied(Target target, VmNetworkPolicyCompiler.Plan desired,
            Set<String> expectedRuntimeStatuses) {
        verifyFirewallBackend(target);
        verifyBarrierGroup(target.apiHost());
        verifySupportedVm(target, desired);
        List<Map<String, Object>> rules = readRules(target);
        verifyControlPrefix(rules, desired.controlRules());
        rejectUnknownEnabled(rules, desired.controlRules().size());
        if (!finalRulesMatch(rules, desired)) {
            throw new ReconcileException("VM 방화벽 최종 readback이 desired policy와 다릅니다.");
        }
        return requireRuntimeStatus(target, expectedRuntimeStatuses);
    }

    public void reconcile(Target target, VmNetworkPolicyCompiler.Plan desired) {
        try {
            verifyFirewallBackend(target);
            verifyBarrierGroup(target.apiHost());
            verifySupportedVm(target, desired);
            List<Map<String, Object>> rules = readRules(target);
            verifyControlPrefix(rules, desired.controlRules());
            rejectUnknownEnabled(rules, desired.controlRules().size());
            if (finalRulesMatch(rules, desired)) {
                return;
            }
            ensureBarrier(target, desired.controlRules().size());
            removeMutable(target, desired.controlRules().size());
            stageMutable(target, desired);
            verifyGuardedDesired(target, desired);
            removeBarrier(target, desired);
            List<Map<String, Object>> applied = readRules(target);
            verifyControlPrefix(applied, desired.controlRules());
            rejectUnknownEnabled(applied, desired.controlRules().size());
            if (!finalRulesMatch(applied, desired)) {
                throw new ReconcileException("VM 방화벽 최종 readback이 desired policy와 다릅니다.");
            }
            verifySupportedVm(target, desired);
        } catch (RuntimeException failure) {
            if (failedClosed(target, desired)) {
                throw new FailedClosedException(message(failure), failure);
            }
            if (failure instanceof ReconcileException reconcile) {
                throw reconcile;
            }
            throw new ReconcileException(message(failure), failure);
        }
    }

    private void verifyFirewallBackend(Target target) {
        Map<String, Object> cluster = proxmox.clusterFirewallOptions(target.apiHost());
        if (!enabled(cluster)) {
            throw new ReconcileException("cluster firewall이 enable되지 않았습니다.");
        }
        Map<String, Object> node = proxmox.nodeFirewallOptions(target.apiHost(), target.node());
        // PVE omits nftables for the legacy backend; an explicit truthy value
        // is the only unsupported selection.
        if (enabledValue(node.get("nftables"))) {
            throw new ReconcileException("노드의 지원되는 legacy firewall backend를 확인할 수 없습니다.");
        }
    }

    private void verifyBarrierGroup(String apiHost) {
        List<Map<String, Object>> groups = proxmox.clusterFirewallGroups(apiHost);
        List<Map<String, Object>> matching = groups.stream()
                .filter(group -> properties.barrierGroup().equals(text(group.get("group"))))
                .toList();
        if (matching.size() != 1
                || !VmFirewallPolicyProperties.BARRIER_COMMENT.equals(
                        text(matching.getFirst().get("comment")))) {
            throw new ReconcileException("immutable VM firewall barrier group을 확인할 수 없습니다.");
        }
        List<Map<String, Object>> rules = proxmox.clusterFirewallGroupRules(
                apiHost, properties.barrierGroup());
        if (rules.size() != 2
                || !containsExactDrop(rules, "in")
                || !containsExactDrop(rules, "out")) {
            throw new ReconcileException("immutable VM firewall barrier group 내용이 변경되었습니다.");
        }
    }

    private static boolean containsExactDrop(List<Map<String, Object>> rules, String type) {
        return rules.stream().map(VmFirewallBarrierReconciler::semantic)
                .anyMatch(rule -> rule.equals(Map.of(
                        "type", type, "action", "DROP", "enable", "1")));
    }

    private void verifySupportedVm(Target target, VmNetworkPolicyCompiler.Plan desired) {
        verifySupportedNic(target);
        verifyOptionsAndIpFilter(target, desired);
    }

    private void verifySupportedNic(Target target) {
        Map<String, Object> config = proxmox.currentVmConfig(
                target.apiHost(), target.node(), target.vmid());
        if (config.keySet().stream().anyMatch(key -> key.matches("net[1-9][0-9]*"))
                || config.containsKey("args")) {
            throw new ReconcileException("net0 밖의 NIC 또는 custom QEMU network args는 지원하지 않습니다.");
        }
        Map<String, String> net0 = parseNet0(config.get("net0"));
        if (!"1".equals(net0.get("firewall"))
                || !validMac(net0.get("virtio"))
                || !target.expectedBridge().equals(net0.get("bridge"))
                || !String.valueOf(target.expectedMtu()).equals(net0.get("mtu"))
                || net0.containsKey("tag") || net0.containsKey("trunks")) {
            throw new ReconcileException("net0 firewall/MAC/bridge/MTU 준비 상태를 확인할 수 없습니다.");
        }
    }

    private void verifyOptionsAndIpFilter(Target target, VmNetworkPolicyCompiler.Plan desired) {
        Map<String, Object> options = proxmox.vmFirewallOptions(
                target.apiHost(), target.node(), target.vmid());
        for (Map.Entry<String, String> expected : desired.options().entrySet()) {
            if (!expected.getValue().equals(text(options.get(expected.getKey())))) {
                throw new ReconcileException("VM firewall immutable options가 desired state와 다릅니다.");
            }
        }
        List<Map<String, Object>> entries = proxmox.vmFirewallIpSet(
                target.apiHost(), target.node(), target.vmid(), "ipfilter-net0");
        if (entries.stream().anyMatch(entry -> enabledValue(entry.get("nomatch"))
                || hasProviderErrors(entry)
                || !IPSET_MARKER.equals(text(entry.get("comment"))))) {
            throw new ReconcileException("ipfilter-net0 entry의 nomatch/error 상태를 거부했습니다.");
        }
        List<String> cidrs = entries.stream().map(entry -> canonicalCidr(entry.get("cidr"))).toList();
        if (!cidrs.equals(desired.ipFilterNet0())) {
            throw new ReconcileException("ipfilter-net0가 할당 IPv4와 다릅니다.");
        }
    }

    private static boolean hasProviderErrors(Map<String, Object> value) {
        Object errors = value.get("errors");
        if (errors == null) {
            return false;
        }
        if (errors instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (errors instanceof List<?> list) {
            return !list.isEmpty();
        }
        return !text(errors).isBlank();
    }

    private void requireRuntimeStatus(Target target, String expected) {
        requireRuntimeStatus(target, Set.of(expected));
    }

    private String requireRuntimeStatus(Target target, Set<String> expected) {
        String actual = text(proxmox.currentVmStatus(
                target.apiHost(), target.node(), target.vmid()).get("status"));
        if (!expected.contains(actual)) {
            throw new ReconcileException("VM runtime 상태가 허용된 전원 상태와 다릅니다.");
        }
        return actual;
    }

    private void ensureOptions(Target target, VmNetworkPolicyCompiler.Plan desired) {
        Map<String, Object> options = proxmox.vmFirewallOptions(
                target.apiHost(), target.node(), target.vmid());
        Set<String> unexpected = new LinkedHashSet<>(options.keySet());
        unexpected.remove("digest");
        unexpected.removeAll(desired.options().keySet());
        if (!unexpected.isEmpty()) {
            throw new ReconcileException("플랫폼 소유가 아닌 VM firewall option이 있습니다.");
        }
        boolean exact = desired.options().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(text(options.get(entry.getKey()))));
        if (!exact) {
            proxmox.setVmFirewallOptions(target.apiHost(), target.node(), target.vmid(),
                    desired.options(), requiredDigest(options));
        }
        Map<String, Object> applied = proxmox.vmFirewallOptions(
                target.apiHost(), target.node(), target.vmid());
        for (Map.Entry<String, String> expected : desired.options().entrySet()) {
            if (!expected.getValue().equals(text(applied.get(expected.getKey())))) {
                throw new ReconcileException("VM firewall option 초기 readback이 다릅니다.");
            }
        }
    }

    private void ensureIpFilter(Target target, VmNetworkPolicyCompiler.Plan desired) {
        List<Map<String, Object>> sets = proxmox.vmFirewallIpSets(
                target.apiHost(), target.node(), target.vmid());
        List<Map<String, Object>> matching = sets.stream()
                .filter(set -> "ipfilter-net0".equals(text(set.get("name")))).toList();
        if (matching.isEmpty()) {
            proxmox.createVmFirewallIpSet(target.apiHost(), target.node(), target.vmid(),
                    "ipfilter-net0", IPSET_MARKER);
            sets = proxmox.vmFirewallIpSets(target.apiHost(), target.node(), target.vmid());
            matching = sets.stream().filter(set -> "ipfilter-net0".equals(text(set.get("name"))))
                    .toList();
        }
        if (matching.size() != 1 || hasProviderErrors(matching.getFirst())
                || !IPSET_MARKER.equals(text(matching.getFirst().get("comment")))) {
            throw new ReconcileException("ipfilter-net0 소유권을 확인할 수 없습니다.");
        }
        List<Map<String, Object>> entries = proxmox.vmFirewallIpSet(
                target.apiHost(), target.node(), target.vmid(), "ipfilter-net0");
        if (entries.isEmpty()) {
            proxmox.createVmFirewallIpSetEntry(target.apiHost(), target.node(), target.vmid(),
                    "ipfilter-net0", desired.ipFilterNet0().getFirst(), IPSET_MARKER);
        }
        verifyOptionsAndIpFilter(target, desired);
    }

    private void ensureControlPrefix(Target target, List<Map<String, String>> controls) {
        for (int index = controls.size() - 1; index >= 0; index--) {
            String marker = controls.get(index).get("comment");
            List<Map<String, Object>> rules = readRules(target);
            List<Map<String, Object>> matching = withComment(rules, marker);
            if (matching.isEmpty()) {
                Map<String, String> disabled = new LinkedHashMap<>(controls.get(index));
                disabled.put("enable", "0");
                proxmox.createVmFirewallRule(target.apiHost(), target.node(), target.vmid(), disabled);
                matching = withComment(readRules(target), marker);
            }
            if (matching.size() != 1) {
                throw new ReconcileException("immutable control rule 소유권을 확인할 수 없습니다.");
            }
            Map<String, String> disabledExpected = new LinkedHashMap<>(controls.get(index));
            disabledExpected.put("enable", "0");
            Map<String, String> shape = semantic(matching.getFirst());
            if (!shape.equals(disabledExpected) && !shape.equals(controls.get(index))) {
                throw new ReconcileException("immutable control rule 내용이 변경되었습니다.");
            }
        }
        for (int index = 0; index < controls.size(); index++) {
            List<Map<String, Object>> rules = readRules(target);
            Map<String, Object> rule = withComment(rules, controls.get(index).get("comment")).getFirst();
            int position = integer(rule.get("pos"));
            if (position != index) {
                int destination = position < index ? index + 1 : index;
                proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(), position,
                        Map.of("moveto", String.valueOf(destination)), digest(rules));
            }
            rules = readRules(target);
            rule = withComment(rules, controls.get(index).get("comment")).getFirst();
            if (!enabled(rule)) {
                proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(),
                        integer(rule.get("pos")), controls.get(index), digest(rules));
            }
        }
        verifyControlPrefix(readRules(target), controls);
    }

    private static String requiredDigest(Map<String, Object> value) {
        String digest = text(value.get("digest"));
        if (digest.isBlank()) {
            throw new ReconcileException("VM firewall option digest를 확인할 수 없습니다.");
        }
        return digest;
    }

    private static String canonicalCidr(Object raw) {
        String value = text(raw);
        return value.contains("/") ? CidrBlock.parse(value).toString()
                : CidrBlock.host(value).toString();
    }

    private static Map<String, String> parseNet0(Object raw) {
        if (!(raw instanceof String value)) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : value.split(",")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2) {
                fields.put(pair[0], pair[1]);
            } else if (!fields.containsKey("virtio")) {
                fields.put("virtio", token);
            }
        }
        return fields;
    }

    private static boolean validMac(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            return false;
        }
        int first = Integer.parseInt(value.substring(0, 2), 16);
        return (first & 1) == 0 && !"00:00:00:00:00:00".equals(value);
    }

    private List<Map<String, Object>> readRules(Target target) {
        return proxmox.vmFirewallRules(target.apiHost(), target.node(), target.vmid());
    }

    private static void verifyControlPrefix(List<Map<String, Object>> actual,
            List<Map<String, String>> controls) {
        if (actual.size() < controls.size()) {
            throw new ReconcileException("immutable control rule이 누락되었습니다.");
        }
        for (int index = 0; index < controls.size(); index++) {
            if (!semantic(actual.get(index)).equals(controls.get(index))) {
                throw new ReconcileException("immutable control rule의 내용이나 순서가 변경되었습니다.");
            }
        }
    }

    private static void rejectUnknownEnabled(List<Map<String, Object>> rules, int start) {
        for (int index = start; index < rules.size(); index++) {
            Map<String, Object> rule = rules.get(index);
            String comment = text(rule.get("comment"));
            boolean owned = BARRIER_MARKER.equals(comment) || comment.startsWith(MUTABLE_PREFIX)
                    || comment.startsWith(CONTROL_PREFIX);
            if (!owned && enabled(rule)) {
                throw new ReconcileException("플랫폼 소유가 아닌 enabled VM firewall rule이 있습니다.");
            }
        }
    }

    private void ensureBarrier(Target target, int controlCount) {
        List<Map<String, Object>> rules = readRules(target);
        List<Map<String, Object>> barriers = withComment(rules, BARRIER_MARKER);
        if (barriers.isEmpty()) {
            Map<String, String> barrier = new LinkedHashMap<>();
            barrier.put("type", "group");
            barrier.put("action", properties.barrierGroup());
            barrier.put("iface", "net0");
            barrier.put("enable", "0");
            barrier.put("comment", BARRIER_MARKER);
            proxmox.createVmFirewallRule(target.apiHost(), target.node(), target.vmid(), barrier);
            rules = readRules(target);
            barriers = withComment(rules, BARRIER_MARKER);
        }
        if (barriers.size() != 1) {
            throw new ReconcileException("VM firewall barrier reference가 중복되거나 누락되었습니다.");
        }
        int position = integer(barriers.getFirst().get("pos"));
        if (position != controlCount) {
            int destination = position < controlCount ? controlCount + 1 : controlCount;
            proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(), position,
                    Map.of("moveto", String.valueOf(destination)), digest(rules));
            rules = readRules(target);
            barriers = withComment(rules, BARRIER_MARKER);
        }
        Map<String, Object> barrier = barriers.getFirst();
        if (!barrierShape(barrier, false) && !barrierShape(barrier, true)) {
            throw new ReconcileException("VM firewall barrier reference 내용이 변경되었습니다.");
        }
        if (!barrierShape(barrier, true)) {
            int pos = integer(barrier.get("pos"));
            proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(), pos,
                    Map.of("enable", "1"),
                    digest(rules));
        }
        rules = readRules(target);
        if (!barrierAt(rules, controlCount)) {
            throw new ReconcileException("VM firewall barrier를 enable한 상태로 확인하지 못했습니다.");
        }
    }

    private void removeMutable(Target target, int controlCount) {
        List<Map<String, Object>> rules = readRules(target);
        List<Integer> positions = rules.stream()
                .filter(rule -> text(rule.get("comment")).startsWith(MUTABLE_PREFIX))
                .map(rule -> integer(rule.get("pos"))).sorted(java.util.Comparator.reverseOrder())
                .toList();
        for (int position : positions) {
            rules = readRules(target);
            if (!barrierAt(rules, controlCount)) {
                throw new ReconcileException("managed rule 삭제 전에 enabled barrier가 없습니다.");
            }
            Map<String, Object> current = rules.stream()
                    .filter(rule -> integer(rule.get("pos")) == position).findFirst().orElseThrow();
            if (!text(current.get("comment")).startsWith(MUTABLE_PREFIX)) {
                throw new ReconcileException("삭제할 managed rule의 위치가 변경되었습니다.");
            }
            proxmox.deleteVmFirewallRule(target.apiHost(), target.node(), target.vmid(),
                    position, digest(rules));
        }
    }

    private void stageMutable(Target target, VmNetworkPolicyCompiler.Plan desired) {
        List<Map<String, String>> rules = desired.mutableRules();
        for (int index = rules.size() - 1; index >= 0; index--) {
            Map<String, String> staged = new LinkedHashMap<>(rules.get(index));
            staged.put("enable", "0");
            proxmox.createVmFirewallRule(target.apiHost(), target.node(), target.vmid(), staged);
            List<Map<String, Object>> current = readRules(target);
            String marker = rules.get(index).get("comment");
            List<Map<String, Object>> matching = withComment(current, marker);
            if (matching.size() != 1 || enabled(matching.getFirst())) {
                throw new ReconcileException("새 managed rule을 disabled 상태로 확인하지 못했습니다.");
            }
            int barrierPos = barrierPosition(current);
            int rulePos = integer(matching.getFirst().get("pos"));
            proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(), rulePos,
                    // PVE moveto names an index in the original list and inserts before
                    // it. The staged rule is at 0, so one past the barrier's observed
                    // index lands immediately below the barrier after the source is skipped.
                    Map.of("moveto", String.valueOf(barrierPos + 1)), digest(current));
            current = readRules(target);
            matching = withComment(current, marker);
            if (matching.size() != 1 || integer(matching.getFirst().get("pos")) <= barrierPosition(current)) {
                throw new ReconcileException("새 managed rule이 barrier 아래에 있지 않습니다.");
            }
            Map<String, String> enabledRule = new LinkedHashMap<>(rules.get(index));
            enabledRule.put("enable", "1");
            proxmox.updateVmFirewallRule(target.apiHost(), target.node(), target.vmid(),
                    integer(matching.getFirst().get("pos")), enabledRule, digest(current));
        }
    }

    private void verifyGuardedDesired(Target target, VmNetworkPolicyCompiler.Plan desired) {
        List<Map<String, Object>> rules = readRules(target);
        requireGuardedDesired(rules, desired);
    }

    private void requireGuardedDesired(List<Map<String, Object>> rules,
            VmNetworkPolicyCompiler.Plan desired) {
        verifyControlPrefix(rules, desired.controlRules());
        int barrier = barrierPosition(rules);
        if (barrier != desired.controlRules().size()
                || !semanticList(rules.subList(barrier + 1, rules.size()), true)
                        .equals(desired.mutableRules())) {
            throw new ReconcileException("barrier 아래의 managed desired rules가 일치하지 않습니다.");
        }
        rejectUnknownEnabled(rules, desired.controlRules().size());
    }

    private void removeBarrier(Target target, VmNetworkPolicyCompiler.Plan desired) {
        List<Map<String, Object>> rules = readRules(target);
        requireGuardedDesired(rules, desired);
        int position = barrierPosition(rules);
        proxmox.deleteVmFirewallRule(target.apiHost(), target.node(), target.vmid(),
                position, digest(rules));
    }

    private boolean failedClosed(Target target, VmNetworkPolicyCompiler.Plan desired) {
        try {
            verifyFirewallBackend(target);
            verifyBarrierGroup(target.apiHost());
            verifySupportedVm(target, desired);
            List<Map<String, Object>> rules = readRules(target);
            verifyControlPrefix(rules, desired.controlRules());
            rejectUnknownEnabled(rules, desired.controlRules().size());
            return barrierAt(rules, desired.controlRules().size());
        } catch (RuntimeException notProven) {
            return false;
        }
    }

    private static boolean finalRulesMatch(List<Map<String, Object>> actual,
            VmNetworkPolicyCompiler.Plan desired) {
        if (actual.stream().anyMatch(rule -> BARRIER_MARKER.equals(text(rule.get("comment"))))) {
            return false;
        }
        List<Map<String, String>> owned = semanticList(actual.stream()
                .filter(rule -> text(rule.get("comment")).startsWith("pickle:vmfw:v1:"))
                .toList(), true);
        List<Map<String, String>> expected = new ArrayList<>(desired.controlRules());
        expected.addAll(desired.mutableRules());
        return owned.equals(expected);
    }

    private static List<Map<String, String>> semanticList(List<Map<String, Object>> rules,
            boolean onlyManaged) {
        return rules.stream()
                .filter(rule -> !onlyManaged
                        || text(rule.get("comment")).startsWith("pickle:vmfw:v1:"))
                .map(VmFirewallBarrierReconciler::semantic).toList();
    }

    private static Map<String, String> semantic(Map<String, ?> rule) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : RULE_FIELDS) {
            Object value = rule.get(field);
            if (value != null && !text(value).isBlank()) {
                result.put(field, text(value));
            }
        }
        return Map.copyOf(result);
    }

    private boolean barrierShape(Map<String, Object> rule, boolean enabled) {
        return semantic(rule).equals(Map.of(
                "type", "group",
                "action", properties.barrierGroup(),
                "enable", enabled ? "1" : "0",
                "iface", "net0",
                "comment", BARRIER_MARKER));
    }

    private boolean barrierAt(List<Map<String, Object>> rules, int position) {
        List<Map<String, Object>> matches = withComment(rules, BARRIER_MARKER);
        return matches.size() == 1 && integer(matches.getFirst().get("pos")) == position
                && barrierShape(matches.getFirst(), true);
    }

    private int barrierPosition(List<Map<String, Object>> rules) {
        List<Map<String, Object>> matches = withComment(rules, BARRIER_MARKER);
        if (matches.size() != 1 || !barrierShape(matches.getFirst(), true)) {
            throw new ReconcileException("enabled barrier reference를 확인할 수 없습니다.");
        }
        return integer(matches.getFirst().get("pos"));
    }

    private static List<Map<String, Object>> withComment(List<Map<String, Object>> rules,
            String comment) {
        return rules.stream().filter(rule -> comment.equals(text(rule.get("comment")))).toList();
    }

    private static String digest(List<Map<String, Object>> rules) {
        Set<String> digests = new LinkedHashSet<>();
        rules.stream().map(rule -> text(rule.get("digest"))).filter(value -> !value.isBlank())
                .forEach(digests::add);
        if (digests.size() != 1) {
            throw new ReconcileException("VM firewall rule digest를 확인할 수 없습니다.");
        }
        return digests.iterator().next();
    }

    private static boolean enabled(Map<String, ?> rule) {
        return enabledValue(rule.get("enable"));
    }

    private static boolean enabledValue(Object raw) {
        String value = text(raw);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException invalid) {
            throw new ReconcileException("VM firewall rule position을 확인할 수 없습니다.");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(Throwable failure) {
        if (failure instanceof ReconcileException
                && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            return failure.getMessage();
        }
        return "Proxmox VM 방화벽 설정 상태를 확인할 수 없습니다.";
    }

    public record Target(String apiHost, String node, int vmid, String expectedBridge,
            int expectedMtu) {
        public Target {
            if (expectedBridge == null
                    || !expectedBridge.matches("[A-Za-z][A-Za-z0-9_.-]{0,14}")
                    || expectedMtu < 1280 || expectedMtu > 1500) {
                throw new IllegalArgumentException("VM network target 준비 정보가 올바르지 않습니다.");
            }
        }
    }

    public static class ReconcileException extends RuntimeException {
        public ReconcileException(String message) { super(message); }
        public ReconcileException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class FailedClosedException extends ReconcileException {
        public FailedClosedException(String message, Throwable cause) { super(message, cause); }
    }
}
