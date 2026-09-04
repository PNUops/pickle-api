package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import kr.ac.pusan.pickle.config.LlmBodyKeyringProperties;
import kr.ac.pusan.pickle.llm.LlmBodyCipher.Field;
import org.junit.jupiter.api.Test;

class LlmBodyCipherTest {

    private static final String PROMPT = "[{\"role\":\"user\",\"content\":\"안녕하세요\"}]";

    private static LlmBodyCipher cipher() {
        String older = Base64.getEncoder().encodeToString(new byte[32]);
        String current = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes());
        return new LlmBodyCipher(new LlmBodyKeyringProperties(
                "v2", "v1=" + older + ",v2=" + current));
    }

    @Test
    void roundTripsUnderTheWriteKeyAndKeepsPlaintextOut() {
        LlmBodyCipher cipher = cipher();
        UUID key = UUID.randomUUID();
        String stored = cipher.encrypt(key, "evt-1", Field.REQUEST, PROMPT);

        assertThat(stored).startsWith("llmb-v1:v2:").doesNotContain("안녕하세요");
        assertThat(cipher.decrypt(key, "evt-1", Field.REQUEST, stored)).isEqualTo(PROMPT);
        assertThat(cipher.writeKeyId()).isEqualTo("v2");
    }

    @Test
    void retiredKeysStillOpenTheRowsTheyWrote() {
        // The point of carrying a key map from day one: rotating the write key
        // must not make older rows unreadable.
        LlmBodyCipher cipher = cipher();
        UUID key = UUID.randomUUID();
        String stored = cipher.encrypt(key, "evt-1", Field.RESPONSE, "answer");

        LlmBodyCipher rotated = new LlmBodyCipher(new LlmBodyKeyringProperties(
                "v3", "v2=" + Base64.getEncoder().encodeToString(
                                "0123456789abcdef0123456789abcdef".getBytes())
                        + ",v3=" + Base64.getEncoder().encodeToString(new byte[32])));
        assertThat(rotated.decrypt(key, "evt-1", Field.RESPONSE, stored)).isEqualTo("answer");
        assertThat(rotated.canRead("v2")).isTrue();
        assertThat(rotated.canRead("v1")).isFalse();
    }

    @Test
    void aadBindsTheCiphertextToItsKeyItsEventAndItsField() {
        // Each of these is a move the access check would not see: repointing a
        // row at another key, moving a ciphertext to another row, and swapping
        // the two ciphertexts a single row carries.
        LlmBodyCipher cipher = cipher();
        UUID key = UUID.randomUUID();
        String stored = cipher.encrypt(key, "evt-1", Field.REQUEST, PROMPT);

        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), "evt-1", Field.REQUEST, stored))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(key, "evt-2", Field.REQUEST, stored))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(key, "evt-1", Field.RESPONSE, stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEventIdCarryingTheFrameSeparatorStillBindsUniquely() {
        // The event id is gateway-supplied text and may contain a colon, so the
        // fixed-width UUID goes first; otherwise two distinct pairs could build
        // the same AAD.
        LlmBodyCipher cipher = cipher();
        UUID key = UUID.randomUUID();
        String stored = cipher.encrypt(key, "a:b", Field.REQUEST, PROMPT);

        assertThat(cipher.decrypt(key, "a:b", Field.REQUEST, stored)).isEqualTo(PROMPT);
        assertThatThrownBy(() -> cipher.decrypt(key, "a", Field.REQUEST, stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownFrameOrRetiredKeyFailsClosedRatherThanReturningRubbish() {
        LlmBodyCipher cipher = cipher();
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> cipher.decrypt(key, "evt-1", Field.REQUEST, "or-mgmt-v1:v2:a:b"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(key, "evt-1", Field.REQUEST, "llmb-v1:gone:a:b"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyKeyringStartsButStoresNothingAndNeverLeaksThePrompt() {
        LlmBodyCipher cipher = new LlmBodyCipher(
                new LlmBodyKeyringProperties("missing", "bad=not-base64"));

        assertThat(cipher.configuredForWrite()).isFalse();
        assertThat(cipher.writeKeyId()).isNull();
        assertThat(cipher.canRead("missing")).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(UUID.randomUUID(), "evt-1", Field.REQUEST, PROMPT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("안녕하세요");
    }
}
