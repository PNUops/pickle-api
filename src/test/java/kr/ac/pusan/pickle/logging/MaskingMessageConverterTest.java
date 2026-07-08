package kr.ac.pusan.pickle.logging;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.common.logging.MaskingMessageConverter;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for the masking patterns (no Spring context); the end-to-end
 * logback wiring is covered by {@link SecretMaskingLogTest}.
 */
class MaskingMessageConverterTest {

    @Test
    void masksProxmoxApiTokenWherever() {
        // Deliberately NOT uuid-shaped: the pre-commit secret scan flags
        // real-looking PVEAPIToken values (tokenid=<uuid>) in staged files.
        String secret = "fake-secret-for-masking-test";
        String header = "Authorization: PVEAPIToken=pickle@pve!pickle-api=" + secret;

        // The Authorization key-value rule additionally swallows the header
        // value, so only assert nothing sensitive survives there.
        String masked = MaskingMessageConverter.mask("calling pve1 with " + header);

        assertThat(masked)
                .doesNotContain(secret)
                .doesNotContain("pickle@pve!pickle-api")
                .contains(MaskingMessageConverter.MASK);

        // Also when it appears mid-sentence without the Authorization key.
        String bare = MaskingMessageConverter.mask("curl -H 'PVEAPIToken=root@pam!ops=" + secret + "'");
        assertThat(bare).doesNotContain(secret).contains("PVEAPIToken=" + MaskingMessageConverter.MASK);
    }
}
