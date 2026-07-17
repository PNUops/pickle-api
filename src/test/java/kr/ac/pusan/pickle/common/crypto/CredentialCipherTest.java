package kr.ac.pusan.pickle.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import kr.ac.pusan.pickle.config.CredentialProperties;
import org.junit.jupiter.api.Test;

/** AES-GCM round trip, per-encryption IV uniqueness, tamper/format/key guards. */
class CredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final CredentialCipher cipher = new CredentialCipher(new CredentialProperties(KEY));

    @Test
    void roundTripsAndNeverRepeatsCiphertext() {
        String encrypted = cipher.encrypt("x7GmQ4vRk2LpWn9sCtYb8Zed");
        assertThat(encrypted).startsWith("v1:").doesNotContain("x7GmQ4vRk2LpWn9sCtYb8Zed");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("x7GmQ4vRk2LpWn9sCtYb8Zed");
        // random IV → same plaintext encrypts differently every time
        assertThat(cipher.encrypt("x7GmQ4vRk2LpWn9sCtYb8Zed")).isNotEqualTo(encrypted);
    }

    @Test
    void rejectsTamperedOrForeignCiphertext() {
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAA=";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v2:abc:def"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("plaintext-from-before-v27"))
                .isInstanceOf(IllegalStateException.class);

        CredentialCipher otherKey = new CredentialCipher(new CredentialProperties(
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes())));
        assertThatThrownBy(() -> otherKey.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnMissingOrWrongSizedKey() {
        assertThatThrownBy(() -> new CredentialCipher(new CredentialProperties("")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher(new CredentialProperties("not-base64!!")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher(new CredentialProperties(
                Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class);
    }
}
