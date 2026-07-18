package kr.ac.pusan.pickle.mfa;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * TOTP (RFC 6238) with the widely-interoperable authenticator defaults: HMAC-SHA1,
 * 6 digits, 30-second step. Verification accepts a ±1 step window (±30s) to
 * tolerate clock skew. Secrets are Base32 (RFC 4648, no padding) — the alphabet
 * authenticator apps expect.
 */
@Service
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20; // 160-bit, RFC 4226 recommendation
    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    private static final int SKEW_STEPS = 1;
    private static final String ISSUER = "Pickle";

    private final SecureRandom random = new SecureRandom();

    /** A fresh Base32 secret for a new enrollment. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * {@code otpauth://totp/Pickle:<email>?secret=...&issuer=Pickle&...} — the
     * URI authenticator apps import (rendered as a QR by the console).
     */
    public String otpauthUri(String email, String secretBase32) {
        // Literal "Issuer:account" label (contract example) — the colon separator
        // and @ are read fine by authenticator apps; only spaces would need
        // escaping and our labels have none.
        return "otpauth://totp/" + ISSUER + ":" + email
                + "?secret=" + secretBase32
                + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** True if {@code code} matches the secret within the ±1 step window at {@code at}. */
    public boolean verify(String secretBase32, String code, Instant at) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        long counter = at.getEpochSecond() / STEP_SECONDS;
        for (int offset = -SKEW_STEPS; offset <= SKEW_STEPS; offset++) {
            if (constantTimeEquals(code, generate(secretBase32, counter + offset))) {
                return true;
            }
        }
        return false;
    }

    /** The RFC 6238 code for a given step counter (package-visible for tests). */
    String generate(String secretBase32, long counter) {
        byte[] key = base32Decode(secretBase32);
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(msg);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("TOTP HMAC failed", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1f));
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String normalized = encoded.trim().replace("=", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
