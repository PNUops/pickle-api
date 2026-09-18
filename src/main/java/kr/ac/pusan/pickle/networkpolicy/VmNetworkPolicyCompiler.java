package kr.ac.pusan.pickle.networkpolicy;

import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Action.ACCEPT;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Direction.IN;
import static kr.ac.pusan.pickle.networkpolicy.VmNetworkRule.Protocol.TCP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles system-owned paths before user rules without adding workspace trust. */
public final class VmNetworkPolicyCompiler {

    public static final int MAX_USER_RULES = 128;

    private VmNetworkPolicyCompiler() {
    }

    public static Plan compile(String guestAddress, String sshGatewayAddress,
            String terminalAddress, List<PublishedPath> publishedPaths, List<VmNetworkRule> userRules) {
        CidrBlock guest = CidrBlock.host(guestAddress);
        if (guest.ipv6()) {
            throw new IllegalArgumentException("현재 VM IP 할당은 IPv4만 지원합니다.");
        }
        if (publishedPaths == null || userRules == null || userRules.size() > MAX_USER_RULES) {
            throw new IllegalArgumentException("VM 네트워크 규칙은 최대 " + MAX_USER_RULES + "개까지 지정할 수 있습니다.");
        }
        List<Map<String, String>> rules = new ArrayList<>();
        rules.add(systemRule(new VmNetworkRule(IN, ACCEPT, TCP,
                CidrBlock.host(sshGatewayAddress), 22, 22), "SSH gateway"));
        rules.add(systemRule(new VmNetworkRule(IN, ACCEPT, TCP,
                CidrBlock.host(terminalAddress), 22, 22), "Web terminal"));
        for (PublishedPath path : publishedPaths) {
            rules.add(systemRule(new VmNetworkRule(IN, ACCEPT, path.protocol(),
                    CidrBlock.host(path.sourceAddress()), path.targetPort(), path.targetPort()),
                    "Published service"));
        }
        for (VmNetworkRule rule : userRules) {
            if (rule == null) {
                throw new IllegalArgumentException("빈 VM 네트워크 규칙은 사용할 수 없습니다.");
            }
            rules.add(rule.pveFields());
        }
        Map<String, String> options = Map.of("enable", "1", "policy_in", "DROP",
                "policy_out", "ACCEPT", "ipfilter", "1", "macfilter", "1",
                "dhcp", "0", "radv", "0", "ndp", "1");
        return new Plan(options, rules, List.of(guest.toString()));
    }

    private static Map<String, String> systemRule(VmNetworkRule rule, String purpose) {
        Map<String, String> result = new LinkedHashMap<>(rule.pveFields());
        result.put("comment", "Managed platform path: " + purpose);
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

    public record Plan(Map<String, String> options, List<Map<String, String>> rules,
            List<String> ipFilterNet0) {
        public Plan {
            options = Map.copyOf(options);
            rules = rules.stream().map(Map::copyOf).toList();
            ipFilterNet0 = List.copyOf(ipFilterNet0);
        }
    }
}
