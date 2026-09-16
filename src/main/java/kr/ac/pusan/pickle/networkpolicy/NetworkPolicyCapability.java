package kr.ac.pusan.pickle.networkpolicy;

import java.util.Set;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import org.springframework.stereotype.Component;

/** No policy may be presented as configured on an unprepared enforcement path. */
@Component
public class NetworkPolicyCapability {

    public static final String SOURCE_ACL = "source-acl-v1";
    private final NetworkPolicyProperties properties;

    public NetworkPolicyCapability(NetworkPolicyProperties properties) {
        this.properties = properties;
    }

    public boolean sourceAclAvailable(Set<String> reportedCapabilities) {
        return properties.enabled() && reportedCapabilities != null
                && reportedCapabilities.contains(SOURCE_ACL);
    }

    public void requireSourceAcl(Set<String> reportedCapabilities) {
        if (!sourceAclAvailable(reportedCapabilities)) {
            throw new IllegalStateException("이 공개 경로에는 출발지 접근 정책이 구성되지 않았습니다.");
        }
    }

    public void requireVmBackend(boolean configured, boolean healthy) {
        if (!properties.enabled() || !configured || !healthy) {
            throw new IllegalStateException("이 노드에는 VM 네트워크 정책이 구성되지 않았습니다.");
        }
    }
}
