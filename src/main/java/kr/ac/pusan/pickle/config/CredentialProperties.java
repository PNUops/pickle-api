package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reversible credential-encryption settings ({@code pickle.credentials.*};
 * key from PICKLE_CREDENTIALS_KEY — base64-encoded 32 bytes, /etc/pickle/api.env).
 */
@ConfigurationProperties(prefix = "pickle.credentials")
public record CredentialProperties(String encryptionKey) {
}
