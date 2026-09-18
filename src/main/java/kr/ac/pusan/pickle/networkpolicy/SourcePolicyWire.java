package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;

/** Exact producer shape consumed by proxy-agent and relay-agent. */
public record SourcePolicyWire(List<String> allowedCidrs) {

    public SourcePolicyWire {
        allowedCidrs = List.copyOf(allowedCidrs);
    }
}
