package kr.ac.pusan.pickle.config;

import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicy;
import kr.ac.pusan.pickle.networkpolicy.CidrBlock;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in network policy settings; an unconfigured deployment keeps its existing behavior. */
@ConfigurationProperties(prefix = "pickle.network-policy")
public record NetworkPolicyProperties(boolean enabled, List<String> campusSourceCidrs) {

    public NetworkPolicyProperties {
        campusSourceCidrs = campusSourceCidrs == null ? List.of()
                : SourcePolicy.parse(campusSourceCidrs, true).cidrValues();
    }

    public SourcePolicy campusPreset(boolean ipv6Supported) {
        if (campusSourceCidrs.isEmpty()) {
            throw new IllegalStateException("교내 출발지 IP 범위가 설정되지 않았습니다.");
        }
        List<CidrBlock> supported = campusSourceCidrs.stream().map(CidrBlock::parse)
                .filter(cidr -> ipv6Supported || !cidr.ipv6()).toList();
        if (supported.isEmpty()) {
            throw new IllegalStateException("이 공개 경로가 지원하는 교내 출발지 IP 범위가 없습니다.");
        }
        return new SourcePolicy(supported);
    }
}
