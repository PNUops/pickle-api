package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterCredentialKeyringProperties;
import org.junit.jupiter.api.Test;

class OpenRouterManagementCredentialCipherTest {

    @Test
    void keyringRoundTripsAndBindsCiphertextToAccountAad() {
        String first = Base64.getEncoder().encodeToString(new byte[32]);
        String second = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes());
        OpenRouterManagementCredentialCipher cipher = new OpenRouterManagementCredentialCipher(
                new OpenRouterCredentialKeyringProperties("new", "old=" + first + ",new=" + second));
        UUID account = UUID.randomUUID();
        String encrypted = cipher.encrypt(account, "management-secret");

        assertThat(encrypted).startsWith("or-mgmt-v1:new:")
                .doesNotContain("management-secret");
        assertThat(cipher.decrypt(account, encrypted)).isEqualTo("management-secret");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyOrInvalidKeyringIsStartupSafeButWriteClosed() {
        OpenRouterManagementCredentialCipher cipher = new OpenRouterManagementCredentialCipher(
                new OpenRouterCredentialKeyringProperties("missing", "bad=not-base64"));
        assertThat(cipher.configuredForWrite()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(UUID.randomUUID(), "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }
}
