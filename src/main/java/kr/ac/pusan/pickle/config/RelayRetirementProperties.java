package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default-off producer gate; consumer capability/ledger may be observed while disabled. */
@ConfigurationProperties(prefix = "pickle.relay-retirement")
public record RelayRetirementProperties(boolean enabled) {
}
