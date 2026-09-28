package kr.ac.pusan.pickle.config;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Candidate-only polling controls; disabled unless an operator opts in explicitly. */
@ConfigurationProperties(prefix = "pickle.openrouter.direct-polling")
public record OpenRouterDirectPollingProperties(
        boolean enabled,
        Set<UUID> accountPublicIds,
        Duration fixedDelay) {

    public OpenRouterDirectPollingProperties {
        accountPublicIds = accountPublicIds == null
                ? Set.of() : Set.copyOf(new TreeSet<>(accountPublicIds));
        fixedDelay = fixedDelay == null ? Duration.ofMinutes(1) : fixedDelay;
        if (fixedDelay.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                    "direct polling fixed-delay must be at least PT1M");
        }
        if (enabled && accountPublicIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "direct polling requires at least one account-public-id allowlist entry");
        }
    }
}
