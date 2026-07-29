package kr.ac.pusan.pickle.relay;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup cross-check between the two places a relay's tunnel address is
 * known: the {@code relays} rows the sync authentication pins on, and the
 * {@code pickle.relay.restricted-source-ips} property that confines those
 * peers to the sync surface. A relay whose address is only in the table
 * authenticates normally but reaches the whole listener, so the drift is
 * silent and has to be reported loudly.
 *
 * <p>Deliberately a one-shot check at startup, not a filter-time lookup: the
 * confinement filter fronts every request on the host and must not take a
 * database round trip per call.</p>
 */
@Component
public class RelaySourceConsistencyCheck {

    private static final Logger log = LoggerFactory.getLogger(RelaySourceConsistencyCheck.class);

    private final RelayRepository relayRepository;
    private final RelayProperties properties;

    public RelaySourceConsistencyCheck(RelayRepository relayRepository,
            RelayProperties properties) {
        this.relayRepository = relayRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        List<String> relaySources = relayRepository.findAllByOrderByIdAsc().stream()
                .filter(Relay::isEnabled)
                .map(Relay::getSourceIp)
                .toList();
        List<String> unconfined = unconfined(properties.restrictedSourceIps(), relaySources);
        if (!unconfined.isEmpty()) {
            log.error("enabled relay source addresses missing from the restricted source list: {}"
                    + " — these peers authenticate normally but are not confined to the sync"
                    + " surface; add them to pickle.relay.restricted-source-ips", unconfined);
        }
    }

    /**
     * Enabled relay addresses that the configured restriction does not cover,
     * in report order and de-duplicated. Blank/absent addresses are skipped:
     * they can match no peer, so they are not a confinement gap.
     */
    static List<String> unconfined(Collection<String> restrictedSources,
            Collection<String> relaySources) {
        Set<String> restricted = Set.copyOf(restrictedSources);
        return relaySources.stream()
                .filter(source -> source != null && !source.isBlank())
                .filter(source -> !restricted.contains(source))
                .distinct()
                .toList();
    }
}
