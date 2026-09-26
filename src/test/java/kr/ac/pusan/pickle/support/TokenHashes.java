package kr.ac.pusan.pickle.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a raw secret, the way the production token stores compute the
 * value they persist. Tests that assert "the row holds the hash of the token
 * the response just handed out" need to recompute it, and the production
 * hasher is package-private to {@code kr.ac.pusan.pickle.auth}.
 *
 * <p>This used to live on the sudo-mode test helper, which is where the LLM
 * API key and relay token suites reached for it; it outlived that feature
 * because neither of those uses had anything to do with reauthentication.
 */
public final class TokenHashes {

    private TokenHashes() {
    }

    public static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
