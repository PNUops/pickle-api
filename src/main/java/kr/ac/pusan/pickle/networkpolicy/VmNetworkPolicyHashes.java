package kr.ac.pusan.pickle.networkpolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;

/** Canonical desired-state hash, independent of DB ids and PVE rule positions. */
public final class VmNetworkPolicyHashes {

    private VmNetworkPolicyHashes() {
    }

    public static String desired(VmFirewallPolicyProperties properties,
            List<VmNetworkPolicyCompiler.PublishedPath> publishedPaths,
            List<VmNetworkRule> rules) {
        StringBuilder canonical = new StringBuilder("vm-policy-v1\n");
        properties.policyFingerprintInputs().forEach(value -> canonical.append(value).append('\n'));
        for (VmNetworkPolicyCompiler.PublishedPath path : publishedPaths) {
            canonical.append("published|").append(path.sourceAddress()).append('|')
                    .append(path.protocol()).append('|').append(path.targetPort()).append('\n');
        }
        for (VmNetworkRule rule : rules) {
            canonical.append(rule.direction()).append('|').append(rule.action()).append('|')
                    .append(rule.protocol()).append('|').append(rule.peer()).append('|')
                    .append(rule.portStart() == null ? "" : rule.portStart()).append('|')
                    .append(rule.portEnd() == null ? "" : rule.portEnd()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
