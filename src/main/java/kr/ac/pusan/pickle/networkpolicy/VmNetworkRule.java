package kr.ac.pusan.pickle.networkpolicy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A typed guest rule; untrusted text is never rendered as a firewall expression. */
public record VmNetworkRule(Direction direction, Action action, Protocol protocol,
        CidrBlock peer, Integer portStart, Integer portEnd) {

    public enum Direction { IN, OUT }
    public enum Action { ACCEPT, DROP }
    public enum Protocol { ANY, TCP, UDP, ICMP, ICMPV6 }

    public VmNetworkRule {
        if (direction == null || action == null || protocol == null || peer == null) {
            throw new IllegalArgumentException("방향, 동작, 프로토콜과 IP 범위를 지정해 주세요.");
        }
        if ((portStart == null) != (portEnd == null)) {
            throw new IllegalArgumentException("포트 범위의 시작과 끝을 모두 지정해 주세요.");
        }
        if (portStart != null && (portStart < 1 || portEnd > 65535 || portStart > portEnd)) {
            throw new IllegalArgumentException("포트 범위는 1부터 65535 사이여야 합니다.");
        }
        if (portStart != null && protocol != Protocol.TCP && protocol != Protocol.UDP) {
            throw new IllegalArgumentException("포트 범위는 TCP 또는 UDP 규칙에만 지정할 수 있습니다.");
        }
        if ((protocol == Protocol.ICMP && peer.ipv6())
                || (protocol == Protocol.ICMPV6 && !peer.ipv6())) {
            throw new IllegalArgumentException("ICMP 프로토콜과 IP 주소 종류가 일치하지 않습니다.");
        }
    }

    public Map<String, String> pveFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", direction.name().toLowerCase(Locale.ROOT));
        fields.put("action", action.name());
        fields.put("enable", "1");
        fields.put("iface", "net0");
        fields.put(direction == Direction.IN ? "source" : "dest", peer.toString());
        if (protocol != Protocol.ANY) {
            fields.put("proto", protocol == Protocol.ICMPV6 ? "ipv6-icmp"
                    : protocol.name().toLowerCase(Locale.ROOT));
        }
        if (portStart != null) {
            fields.put("dport", portStart.equals(portEnd) ? portStart.toString()
                    : portStart + ":" + portEnd);
        }
        return Map.copyOf(fields);
    }
}
