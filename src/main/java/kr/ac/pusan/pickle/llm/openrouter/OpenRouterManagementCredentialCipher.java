package kr.ac.pusan.pickle.llm.openrouter;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kr.ac.pusan.pickle.config.OpenRouterCredentialKeyringProperties;
import org.springframework.stereotype.Component;

/** Dedicated AES-256-GCM keyring with account-bound authenticated data. */
@Component
public class OpenRouterManagementCredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String FRAME = "or-mgmt-v1";

    private final OpenRouterCredentialKeyringProperties properties;
    private final SecureRandom random = new SecureRandom();

    public OpenRouterManagementCredentialCipher(OpenRouterCredentialKeyringProperties properties) {
        this.properties = properties;
    }

    public boolean configuredForWrite() {
        String id = properties.writeKeyId();
        return validKeyId(id) && keys().containsKey(id);
    }

    public String encrypt(UUID accountId, String plaintext) {
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
            cipher.updateAAD(aad(accountId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return FRAME + ":" + keyId + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OpenRouter management credential encryption failed", e);
        }
    }

    public String decrypt(UUID accountId, String stored) {
        String[] parts = stored.split(":", 4);
        if (parts.length != 4 || !FRAME.equals(parts[0])) {
            throw new IllegalStateException("unknown OpenRouter management credential frame");
        }
        SecretKeySpec key = keys().get(parts[1]);
        if (key == null) {
            throw unavailable();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[2])));
            cipher.updateAAD(aad(accountId));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("OpenRouter management credential decryption failed", e);
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

    private static byte[] aad(UUID accountId) {
        return ("openrouter-account:" + accountId).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean validKeyId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("OpenRouter management credential keyring is unavailable");
    }
}
