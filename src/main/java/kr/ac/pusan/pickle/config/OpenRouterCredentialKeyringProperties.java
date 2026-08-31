package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dedicated encryption keyring for OpenRouter management credentials.
 *
 * <p>{@code readKeys} is a comma-separated {@code keyId=base64Key} list. The
 * API may start with an empty keyring so every non-credential surface remains
 * available; credential mutations fail closed until a write id and its
 * 32-byte AES key are configured.</p>
 */
@ConfigurationProperties(prefix = "pickle.openrouter-credential-keyring")
public record OpenRouterCredentialKeyringProperties(String writeKeyId, String readKeys) {
}
