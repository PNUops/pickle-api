package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dedicated encryption keyring for captured LLM prompt and response text.
 *
 * <p>Deliberately not {@code CredentialCipher}'s key. That one already opens
 * four datasets and has no rotation path, so adding a fifth would widen a
 * blast radius nobody can shrink again. This keyring carries a key map from
 * its first day for exactly that reason.</p>
 *
 * <p>{@code readKeys} is a comma-separated {@code keyId=base64Key} list. The
 * API may start with an empty keyring, because body capture is an off-by-
 * default option and coupling the whole service's startup to its key would be
 * the wrong dependency. With no write key configured, ingest stores nothing
 * and says so; reads report the affected rows as unreadable rather than
 * failing.</p>
 */
@ConfigurationProperties(prefix = "pickle.llm-body-keyring")
public record LlmBodyKeyringProperties(String writeKeyId, String readKeys) {
}
