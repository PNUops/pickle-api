package kr.ac.pusan.pickle.common.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one hashing rule for secrets this platform issues and later recognizes.
 *
 * <p>Hex sha256 of the whole secret, lowercase. It is shared rather than
 * repeated because two of the things that verify against it run in other
 * processes — the relay agent and the LLM gateway both hash what a caller
 * presented and compare — so a second implementation here would not fail
 * loudly, it would just stop recognizing valid credentials.
 */
public final class Secrets {

    private Secrets() {
    }

    /** Lowercase hex sha256 of the value, as stored and as compared. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
