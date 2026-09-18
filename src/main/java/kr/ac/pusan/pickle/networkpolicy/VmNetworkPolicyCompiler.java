package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.ACCEPT;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.IN;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.TCP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;

/** Compiles system-owned paths before user rules without adding workspace trust. */
public final class VmNetworkPolicyCompiler {

    public static final int MAX_USER_RULES = 128;

    private VmNetworkPolicyCompiler() {
    }

    public static Plan compile(String guestAddress, VmFirewallPolicyProperties properties,
            List<PublishedPath> publishedPaths, List<VmNetworkRule> userRules) {
        CidrBlock guest = CidrBlock.host(guestAddress);
        if (guest.ipv6()) {
            throw new IllegalArgumentException("현재 VM IP 할당은 IPv4만 지원합니다.");
        }
        if (publishedPaths == null || userRules == null || userRules.size() > MAX_USER_RULES) {
            throw new IllegalArgumentException("VM 네트워크 규칙은 최대 " + MAX_USER_RULES + "개까지 지정할 수 있습니다.");
        }
        List<Map<String, String>> controls = new ArrayList<>();
        LinkedHashSet<String> sshSources = new LinkedHashSet<>(properties.sshGatewaySourceIps());
        sshSources.addAll(properties.terminalSourceIps());
        int controlIndex = 0;
        for (String address : sshSources) {
            controls.add(systemRule(new VmNetworkRule(IN, ACCEPT, TCP,
                    CidrBlock.host(address), 22, 22), "control:ssh:" + controlIndex++));
        }
        controls.add(ipv6Drop("in"));
        controls.add(ipv6Drop("out"));
        List<Map<String, String>> mutable = new ArrayList<>();
        for (int index = 0; index < publishedPaths.size(); index++) {
            PublishedPath path = publishedPaths.get(index);
            mutable.add(systemRule(new VmNetworkRule(IN, ACCEPT, path.protocol(),
                    CidrBlock.host(path.sourceAddress()), path.targetPort(), path.targetPort()),
                    "mutable:published:" + index));
        }
        for (int index = 0; index < userRules.size(); index++) {
            VmNetworkRule rule = userRules.get(index);
            if (rule == null) {
                throw new IllegalArgumentException("빈 VM 네트워크 규칙은 사용할 수 없습니다.");
            }
            Map<String, String> rendered = new LinkedHashMap<>(rule.pveFields());
            rendered.put("comment", "pickle:vmfw:v1:mutable:user:" + index);
            mutable.add(Map.copyOf(rendered));
        }
        Map<String, String> options = Map.of("enable", "1", "policy_in", "DROP",
                "policy_out", "ACCEPT", "ipfilter", "1", "macfilter", "1",
                "dhcp", "0", "radv", "0", "ndp", "0");
        return new Plan(options, controls, mutable, List.of(guest.toString()));
    }

    private static Map<String, String> systemRule(VmNetworkRule rule, String purpose) {
        Map<String, String> result = new LinkedHashMap<>(rule.pveFields());
        result.put("comment", "pickle:vmfw:v1:" + purpose);
        return Map.copyOf(result);
    }

    private static Map<String, String> ipv6Drop(String direction) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("type", direction);
        result.put("action", "DROP");
        result.put("enable", "1");
        result.put("iface", "net0");
        result.put("in".equals(direction) ? "source" : "dest", "::/0");
        result.put("comment", "pickle:vmfw:v1:control:ipv6:" + direction);
        return Map.copyOf(result);
    }

    /** Only a trusted source and the actually published target port are opened. */
    public record PublishedPath(String sourceAddress, VmNetworkRule.Protocol protocol, int targetPort) {
        public PublishedPath {
            CidrBlock.host(sourceAddress);
            if ((protocol != TCP && protocol != VmNetworkRule.Protocol.UDP)
                    || targetPort < 1 || targetPort > 65535) {
                throw new IllegalArgumentException("공개 경로에는 TCP/UDP와 유효한 대상 포트가 필요합니다.");
            }
        }
    }

    public record Plan(Map<String, String> options, List<Map<String, String>> controlRules,
            List<Map<String, String>> mutableRules,
            List<String> ipFilterNet0) {
        public Plan {
            options = Map.copyOf(options);
            controlRules = controlRules.stream().map(Map::copyOf).toList();
            mutableRules = mutableRules.stream().map(Map::copyOf).toList();
            ipFilterNet0 = List.copyOf(ipFilterNet0);
        }
    }
}
