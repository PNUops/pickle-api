package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyStore.Stored;
import org.springframework.stereotype.Component;

/** Resolves stored or environment-default policy without ever weakening an explicit row. */
@Component
public class SourcePolicyProducer {

    private final NetworkPolicyProperties properties;
    private final SourcePolicyStore store;

    public SourcePolicyProducer(NetworkPolicyProperties properties, SourcePolicyStore store) {
        this.properties = properties;
        this.store = store;
    }

    public Optional<SourcePolicyWire> domain(long domainId, boolean armed) {
        return resolve(store.domain(domainId), armed);
    }

    public Optional<SourcePolicyWire> portMapping(long mappingId, boolean armed) {
        return resolve(store.portMapping(mappingId), armed);
    }

    private Optional<SourcePolicyWire> resolve(Optional<Stored> stored, boolean armed) {
        if (stored.isEmpty() && !armed && !properties.enabled()) {
            return Optional.empty();
        }
        if (!properties.enabled()) {
            throw new SourcePolicyUnavailableException(
                    "출발지 정책 기능이 꺼져 있어 저장된 정책을 적용할 수 없습니다.");
        }
        return Optional.of(new SourcePolicyWire(stored.map(Stored::allowedCidrs).orElse(List.of())));
    }
}
