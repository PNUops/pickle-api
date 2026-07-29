package kr.ac.pusan.pickle.relay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Per-relay sync tokens: 32 random bytes as 64 lowercase hex chars (hex only —
 * no {@code =} or other separator-hostile characters in secrets), stored only
 * as their sha256 hex hash.
 */
final class RelayTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RelayTokens() {
    }

    /** 256-bit random token, 64 hex chars. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
