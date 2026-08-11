package kr.ac.pusan.pickle.llm;

import java.security.SecureRandom;
import kr.ac.pusan.pickle.common.text.Secrets;

/**
 * The plaintext an LLM API key is, and what is kept of it.
 *
 * <p>The shape is fixed by the gateway, not chosen here: it recognizes a
 * bearer by hashing whatever was presented, and students paste the value into
 * an OpenAI SDK. So it stays URL- and header-safe, carries a prefix a person
 * can recognize in a support request, and has enough entropy that guessing is
 * not a strategy — 43 base62 characters is a little over 256 bits.
 */
public final class LlmApiKeyTokens {

    /** What every issued key starts with, so one is recognizable on sight. */
    public static final String PREFIX = "pickle-";
    /** How much of the plaintext a list may show. */
    public static final int VISIBLE_PREFIX_LENGTH = PREFIX.length() + 6;

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int RANDOM_LENGTH = 43;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LlmApiKeyTokens() {
    }

    /** A fresh plaintext key. Shown once, then only its hash survives. */
    public static String newToken() {
        StringBuilder token = new StringBuilder(PREFIX.length() + RANDOM_LENGTH);
        token.append(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }

    /** What is stored, and what the gateway computes from a presented token. */
    public static String hash(String token) {
        return Secrets.sha256Hex(token);
    }

    /**
     * The leading characters kept alongside the hash, so a list can tell two
     * keys apart without holding anything that authenticates.
     */
    public static String visiblePrefix(String token) {
        return token.substring(0, Math.min(VISIBLE_PREFIX_LENGTH, token.length()));
    }
}
