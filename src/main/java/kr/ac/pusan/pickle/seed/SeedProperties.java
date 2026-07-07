package kr.ac.pusan.pickle.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dev/test seed accounts ({@code pickle.seed.*}, from PICKLE_SEED_* env vars).
 * Defaults are documented in README.md and are dev-only.
 */
@ConfigurationProperties(prefix = "pickle.seed")
public record SeedProperties(
        String sysadminEmail,
        String sysadminPassword,
        String orgadminEmail,
        String orgadminPassword) {
}
