package kr.ac.pusan.pickle.llm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kr.ac.pusan.pickle.config.LlmBodyKeyringProperties;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for captured prompt and response text, bound to the record it
 * belongs to.
 *
 * <p>It lives beside the keys rather than in {@code common/crypto} on purpose.
 * The decision this component exists to carry out is "this key is not shared",
 * and a home in the common package is an invitation for the next dataset to
 * reuse it -- which is the failure {@code CredentialCipher} already is.</p>
 */
@Component
public class LlmBodyCipher {

    /** Which half of a record a ciphertext is, bound into its AAD. */
    public enum Field { REQUEST, RESPONSE }

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String FRAME = "llmb-v1";

    private final LlmBodyKeyringProperties properties;
    private final SecureRandom random = new SecureRandom();

    public LlmBodyCipher(LlmBodyKeyringProperties properties) {
        this.properties = properties;
    }

    /** Whether new text can be stored at all. False means ingest keeps nothing. */
    public boolean configuredForWrite() {
        String id = properties.writeKeyId();
        return validKeyId(id) && keys().containsKey(id);
    }

    /**
     * The key id new rows are written under, for {@code cipher_key_id}. Null
     * when nothing is configured, which callers must treat as "do not store".
     */
    public String writeKeyId() {
        return configuredForWrite() ? properties.writeKeyId() : null;
    }

    /** Whether a stored value can still be opened, without throwing to find out. */
    public boolean canRead(String cipherKeyId) {
        return cipherKeyId != null && keys().containsKey(cipherKeyId);
    }

    public String encrypt(UUID keyPublicId, String eventId, Field field, String plaintext) {
        String keyId = properties.writeKeyId();
        SecretKeySpec key = keyId == null ? null : keys().get(keyId);
        if (key == null) {
            throw unavailable();
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyPublicId, eventId, field));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return FRAME + ":" + keyId + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("LLM body encryption failed", e);
        }
    }

    public String decrypt(UUID keyPublicId, String eventId, Field field, String stored) {
        String[] parts = stored.split(":", 4);
        if (parts.length != 4 || !FRAME.equals(parts[0])) {
            throw new IllegalStateException("unknown LLM body frame");
        }
        SecretKeySpec key = keys().get(parts[1]);
        if (key == null) {
            throw unavailable();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[2])));
            cipher.updateAAD(aad(keyPublicId, eventId, field));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("LLM body decryption failed", e);
        }
    }

    private Map<String, SecretKeySpec> keys() {
        Map<String, SecretKeySpec> result = new LinkedHashMap<>();
        String raw = properties.readKeys();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String[] pair = item.strip().split("=", 2);
            if (pair.length != 2 || !validKeyId(pair[0]) || pair[1].isBlank()) {
                continue;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(pair[1]);
                if (decoded.length == 32) {
                    result.put(pair[0], new SecretKeySpec(decoded, "AES"));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid entries stay unavailable; API startup remains possible.
            }
        }
        return result;
    }

    /**
     * Binds a ciphertext to the row it was written for. Three things travel in
     * it, and each one closes a move that authorization code would not see:
     *
     * <ul>
     *   <li>the key's public id, so rewriting a row's {@code key_id} to point
     *       at your own key yields something that will not decrypt. This is a
     *       second layer under the access check rather than a replacement for
     *       it: the row would still be <i>listed</i> under the key it now names,
     *       because listing filters on the key and only the reading is bound.
     *       What the binding buys is that the text stays shut;
     *   <li>the event id, so a ciphertext cannot be moved to another row;
     *   <li>the field, because a row carries two ciphertexts and without it
     *       they are interchangeable. The OpenRouter precedent had one value
     *       per row and so never needed this.
     * </ul>
     *
     * <p>The fixed-width UUID precedes the variable-width event id so that no
     * two distinct pairs can produce the same byte string: the event id is
     * gateway-supplied text and may itself contain a colon.</p>
     */
    private static byte[] aad(UUID keyPublicId, String eventId, Field field) {
        return ("llm-body:" + field.name().toLowerCase(Locale.ROOT)
                + ":" + keyPublicId + ":" + eventId).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean validKeyId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("LLM body keyring is unavailable");
    }
}
