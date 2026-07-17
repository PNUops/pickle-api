package kr.ac.pusan.pickle.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Golden tests for {@link SshPublicKeyParser}. The fixtures were produced with
 * OpenSSH locally, and the expected fingerprints are the {@code ssh-keygen -lf}
 * output for the same keys, e.g.:
 *
 * <pre>
 *   ssh-keygen -t ed25519 -N '' -C fixture@pickle -f k_ed
 *   ssh-keygen -lf k_ed.pub
 *   # 256 SHA256:/YMI/y63bR/1ageR+EQuaaCP72ObF73MApQtZH26tW0 fixture@pickle (ED25519)
 * </pre>
 *
 * so a parser change that diverged from OpenSSH's canonical fingerprint would
 * fail here.
 */
class SshPublicKeyParserTest {

    private static final String ED25519_PUB =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/cMyIC9ZMs5ssc "
                    + "fixture@pickle";
    private static final String ED25519_FP =
            "SHA256:/YMI/y63bR/1ageR+EQuaaCP72ObF73MApQtZH26tW0";

    private static final String RSA_PUB =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDED6jCdDGeTAkaAMyr7lvFO9qNaIUMJdu6qeWF005hVnON"
                    + "cWMKIvKHbCHVzsHn9REkgQ9qLn2h60WtyjbhYpf5yDwvTGBzsGzSUEAmruI7S1gU9bS0rpBMuf1"
                    + "40fKcyoE5LxMOf1K/JdBAwKE0gJC+xcrw20viHkpvghc9hir/bWa5dY9gjnX2H+sEzJD6fgWCGkQ"
                    + "tPGnjM0OeRooSKfsCl3wOaBmelug5FQu2iawIDGZIiKGTQajA+Sd5UiLiEd0WYtVwxu6W4jlkXT3"
                    + "LWsS6YSQJr15S/k+dIuZAq1vuDJzLEjgDjJeIJFdl36Dgi1IsHv+7Vizmr2EzxRTYqGz9 "
                    + "fixture-rsa@pickle";
    private static final String RSA_FP =
            "SHA256:g1A4pfkmf+XmceT0lCSr03EvAlWpZ56PXNaOlzaDDOU";

    // ecdsa-sha2-nistp256 — an accepted-list miss (FIDO/ecdsa rejected on purpose).
    private static final String ECDSA_PUB =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBP/TR8FpwtKe"
                    + "2qQyodrbWIUfOV+Tx47Qy1ctZFa/eMnEFVHj8Cl2DHf3a5Ydq9EEGkCTnpQFeXy5lcD6KWCLm0Y= "
                    + "fixture-ecdsa@pickle";

    // ssh-rsa 1024-bit — accepted type but below the 2048-bit floor.
    private static final String RSA_1024_PUB =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQDd85ONx8n2wu4sK1fBNAFIHAm0X9o4+BZno7Ar7FkNuaim"
                    + "CBbWwZJHhO6s/boAIl8zxyUdHpLwKfRQC3Kbqzj5IcpZRucfVtCk52KIy820igHr9iM4WmKQeGEc8"
                    + "C34Bm+C2bgQjuqdTeLgKX0Csy18+U4CYkS9cJc+CM3HWfjonw== weak@pickle";

    private final SshPublicKeyParser parser = new SshPublicKeyParser();

    @Test
    void parsesEd25519WithCanonicalFingerprintAndStripsComment() {
        ParsedSshKey parsed = parser.parse(ED25519_PUB);
        assertThat(parsed.algorithm()).isEqualTo(SshKeyAlgorithm.ED25519);
        assertThat(parsed.fingerprint()).isEqualTo(ED25519_FP);
        assertThat(parsed.normalizedLine())
                .isEqualTo("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/"
                        + "cMyIC9ZMs5ssc");
    }

    @Test
    void parsesRsa2048WithCanonicalFingerprint() {
        ParsedSshKey parsed = parser.parse(RSA_PUB);
        assertThat(parsed.algorithm()).isEqualTo(SshKeyAlgorithm.RSA);
        assertThat(parsed.fingerprint()).isEqualTo(RSA_FP);
    }

    @Test
    void toleratesLeadingTrailingWhitespaceAndNoComment() {
        String noComment = "  ssh-ed25519 "
                + "AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/cMyIC9ZMs5ssc  ";
        assertThat(parser.parse(noComment).fingerprint()).isEqualTo(ED25519_FP);
    }

    @Test
    void rejectsEcdsaAsUnacceptedType() {
        assertThatThrownBy(() -> parser.parse(ECDSA_PUB))
                .isInstanceOf(SshPublicKeyParseException.class)
                .hasMessageContaining("지원하지 않는");
    }

    @Test
    void rejectsRsaBelow2048() {
        assertThatThrownBy(() -> parser.parse(RSA_1024_PUB))
                .isInstanceOf(SshPublicKeyParseException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(SshPublicKeyParseException.class);
        assertThatThrownBy(() -> parser.parse("ssh-ed25519"))
                .isInstanceOf(SshPublicKeyParseException.class);
        assertThatThrownBy(() -> parser.parse("ssh-ed25519 not-base64!!!"))
                .isInstanceOf(SshPublicKeyParseException.class);
    }

    @Test
    void rejectsDeclaredTypeMismatchingBlob() {
        // ed25519 body labelled as ssh-rsa: embedded type != declared type.
        String mismatched = "ssh-rsa "
                + "AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/cMyIC9ZMs5ssc";
        assertThatThrownBy(() -> parser.parse(mismatched))
                .isInstanceOf(SshPublicKeyParseException.class)
                .hasMessageContaining("일치하지");
    }
}
