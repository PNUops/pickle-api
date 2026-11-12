package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Preparation only; enabling this feature never changes the existing vendor polling cadence. */
@ConfigurationProperties(prefix = "pickle.llm.usage-alerts")
public record LlmUsageAlertProperties(boolean enabled) { }
