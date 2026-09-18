package kr.ac.pusan.pickle.networkpolicy;

import java.util.HashSet;
import java.util.List;

/** An explicit source allowlist. An empty policy denies all sources. */
public record SourcePolicy(List<CidrBlock> allowedCidrs) {

    public static final int MAX_CIDRS = 128;

    public SourcePolicy {
        if (allowedCidrs == null || allowedCidrs.size() > MAX_CIDRS
                || allowedCidrs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("출발지 CIDR은 최대 " + MAX_CIDRS + "개까지 지정할 수 있습니다.");
        }
        allowedCidrs = List.copyOf(allowedCidrs);
        if (new HashSet<>(allowedCidrs).size() != allowedCidrs.size()) {
            throw new IllegalArgumentException("중복된 출발지 CIDR을 제거해 주세요.");
        }
    }

    public static SourcePolicy parse(List<String> cidrs, boolean ipv6Supported) {
        if (cidrs == null || cidrs.size() > MAX_CIDRS) {
            throw new IllegalArgumentException("출발지 CIDR 목록을 지정해 주세요.");
        }
        SourcePolicy policy = new SourcePolicy(cidrs.stream().map(CidrBlock::parse).toList());
        if (!ipv6Supported && policy.allowedCidrs.stream().anyMatch(CidrBlock::ipv6)) {
            throw new IllegalArgumentException("이 공개 경로는 IPv6 출발지를 지원하지 않습니다.");
        }
        return policy;
    }

    public boolean allows(String address) {
        return allowedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
    }

    public List<String> cidrValues() {
        return allowedCidrs.stream().map(CidrBlock::toString).toList();
    }
}
