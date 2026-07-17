package kr.ac.pusan.pickle.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kr.ac.pusan.pickle.config.CredentialProperties;
import org.springframework.stereotype.Component;

/**
 * Reversible encryption for credentials the platform must be able to show back
 * to the user (today: VM initial passwords — the 2026-07-17 "always
 * re-viewable" policy). AES-256-GCM with a random 96-bit IV per encryption;
 * the key lives only in the env file, so a DB dump alone cannot decrypt.
 *
 * <p>Stored form is {@code v1:<base64 iv>:<base64 ciphertext||tag>}. The
 * {@code v1} prefix is a key id: rotation adds {@code v2} and a key map while
 * old rows stay readable — never reuse a prefix with a different key.
 */
@Component
public class CredentialCipher {

    private static final String KEY_ID = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(CredentialProperties properties) {
        if (properties.encryptionKey() == null || properties.encryptionKey().isBlank()) {
            throw new IllegalStateException(
                    "pickle.credentials.encryption-key is not set. Provide PICKLE_CREDENTIALS_KEY "
                            + "(base64-encoded 32 bytes) via /etc/pickle/api.env.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "pickle.credentials.encryption-key must be base64 (openssl rand -base64 32).", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "pickle.credentials.encryption-key must decode to exactly 32 bytes (AES-256).");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return KEY_ID + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("credential encryption failed", e);
        }
    }

    /** @throws IllegalStateException on unknown format/key id or tampered data. */
    public String decrypt(String stored) {
        String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !KEY_ID.equals(parts[0])) {
            throw new IllegalStateException("unknown credential ciphertext format/key id");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("credential decryption failed", e);
        }
    }
}
