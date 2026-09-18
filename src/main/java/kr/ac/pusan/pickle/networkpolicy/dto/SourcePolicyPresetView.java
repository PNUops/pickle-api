package kr.ac.pusan.pickle.networkpolicy.dto;

import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyTarget;

/** A read-only preset resolved to the exact CIDR snapshot an editor may save. */
public record SourcePolicyPresetView(
        String key,
        SourcePolicyTarget target,
        List<String> allowedCidrs) {
}
