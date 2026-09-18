package kr.ac.pusan.pickle.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.networkpolicy.CidrBlock;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default-off VM firewall inputs. No production address is compiled into the application. */
@ConfigurationProperties(prefix = "pickle.vm-firewall")
public record VmFirewallPolicyProperties(
        boolean enabled,
        String barrierGroup,
        List<String> sshGatewaySourceIps,
        List<String> terminalSourceIps,
        List<String> proxySourceIps,
        List<String> relaySourceIps) {

    public static final String BARRIER_COMMENT = "pickle immutable vm barrier v1";

    public VmFirewallPolicyProperties {
        barrierGroup = barrierGroup == null ? "" : barrierGroup.strip();
        sshGatewaySourceIps = hostAddresses(sshGatewaySourceIps);
        terminalSourceIps = hostAddresses(terminalSourceIps);
        proxySourceIps = hostAddresses(proxySourceIps);
        relaySourceIps = hostAddresses(relaySourceIps);
        if (enabled) {
            validateConfigured(barrierGroup, sshGatewaySourceIps, terminalSourceIps,
                    proxySourceIps, relaySourceIps);
        }
    }

    public void requireConfigured() {
        validateConfigured(barrierGroup, sshGatewaySourceIps, terminalSourceIps,
                proxySourceIps, relaySourceIps);
    }

    private static void validateConfigured(String barrierGroup, List<String> sshGatewaySourceIps,
            List<String> terminalSourceIps, List<String> proxySourceIps,
            List<String> relaySourceIps) {
        if (barrierGroup == null || !barrierGroup.matches("[A-Za-z][A-Za-z0-9_-]{1,19}")) {
            throw new IllegalStateException("VM 방화벽 barrier group 이름을 확인해 주세요.");
        }
        if (sshGatewaySourceIps.isEmpty() || terminalSourceIps.isEmpty()
                || proxySourceIps.isEmpty() || relaySourceIps.isEmpty()) {
            throw new IllegalStateException("VM 방화벽 system source IP를 모두 설정해 주세요.");
        }
    }

    public List<String> policyFingerprintInputs() {
        List<String> values = new ArrayList<>();
        values.add("barrier=" + barrierGroup);
        add(values, "ssh", sshGatewaySourceIps);
        add(values, "terminal", terminalSourceIps);
        add(values, "proxy", proxySourceIps);
        add(values, "relay", relaySourceIps);
        return List.copyOf(values);
    }

    private static void add(List<String> target, String kind, List<String> values) {
        values.forEach(value -> target.add(kind + "=" + value));
    }

    private static List<String> hostAddresses(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException("VM 방화벽 source IP가 비어 있습니다.");
            }
            CidrBlock block = CidrBlock.host(value.strip());
            if (block.ipv6()) {
                throw new IllegalArgumentException("VM 방화벽 system source는 현재 IPv4만 지원합니다.");
            }
            canonical.add(block.toString().replace("/32", ""));
        }
        return List.copyOf(canonical);
    }
}
