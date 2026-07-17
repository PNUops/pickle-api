package kr.ac.pusan.pickle.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link SshKeyPairGenerator} produces a byte-correct OpenSSH key pair.
 *
 * <p>Two independent checks: a pinned golden (the fixed-seed run's public line
 * and fingerprint, captured once and cross-checked with {@code ssh-keygen}), and
 * a runtime cross-check that feeds the generated private-key PEM to
 * {@code ssh-keygen -y}/{@code -lf} — proving the {@code openssh-key-v1}
 * serialization round-trips through real OpenSSH. The runtime check self-skips
 * where {@code ssh-keygen} is absent; the golden always runs.</p>
 *
 * <p>Golden verification record (2026-07-18): the fixed-seed private PEM was
 * written to disk and {@code ssh-keygen -y -f} re-derived exactly
 * {@link #GOLDEN_PUBLIC_LINE}; {@code ssh-keygen -lf} reported
 * {@link #GOLDEN_FINGERPRINT}.</p>
 */
class SshKeyPairGeneratorTest {

    private static final String GOLDEN_PUBLIC_LINE =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJi3ulpJ9QgJsDAtQesFM3nfIBSpxxWsRDVlNnsliC4Z";
    private static final String GOLDEN_FINGERPRINT =
            "SHA256:Kx7vGACsR0pMdMrSE/+A5lZRV1NLzDvSucIQmArRr2k";

    private final SshPublicKeyParser parser = new SshPublicKeyParser();

    /** Same seed → identical bytes, so downloads are reproducible. */
    @Test
    void generationIsDeterministicForAFixedSeed() {
        GeneratedSshKeyPair first = fixedSeedGenerator().generate("pickle@example");
        GeneratedSshKeyPair second = fixedSeedGenerator().generate("pickle@example");
        assertThat(first.publicKeyLine()).isEqualTo(second.publicKeyLine());
        assertThat(first.privateKeyPem()).isEqualTo(second.privateKeyPem());
    }

    @Test
    void publicLineParsesAsEd25519AndFingerprintIsSelfConsistent() {
        GeneratedSshKeyPair pair = fixedSeedGenerator().generate("pickle@example");
        ParsedSshKey parsed = parser.parse(pair.publicKeyLine());
        assertThat(parsed.algorithm()).isEqualTo(SshKeyAlgorithm.ED25519);
        assertThat(pair.publicKeyLine()).isEqualTo(GOLDEN_PUBLIC_LINE);
        assertThat(parsed.fingerprint()).isEqualTo(GOLDEN_FINGERPRINT);
    }

    @Test
    void privateKeyPemHasOpensshEnvelope() {
        String pem = fixedSeedGenerator().generate("pickle@example").privateKeyPem();
        assertThat(pem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        assertThat(pem).endsWith("-----END OPENSSH PRIVATE KEY-----\n");
    }

    /** Round-trips the private PEM through real OpenSSH (skips if unavailable). */
    @Test
    void opensshRederivesTheSamePublicKeyAndFingerprint(@TempDir Path dir) throws Exception {
        assumeTrue(commandExists("ssh-keygen"), "ssh-keygen not available");
        GeneratedSshKeyPair pair = fixedSeedGenerator().generate("pickle@example");

        Path keyFile = dir.resolve("id_ed25519_pickle");
        Files.writeString(keyFile, pair.privateKeyPem());
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

        String rederived = runCommand(dir, "ssh-keygen", "-y", "-f", keyFile.toString()).strip();
        // ssh-keygen -y re-derives "<type> <base64> <comment>"; our normalized
        // line has no comment, so it must be the prefix of what OpenSSH prints.
        assertThat(rederived).startsWith(pair.publicKeyLine());

        String fpLine = runCommand(dir, "ssh-keygen", "-lf", keyFile.toString());
        assertThat(fpLine).contains(parser.parse(pair.publicKeyLine()).fingerprint());
    }

    private static SshKeyPairGenerator fixedSeedGenerator() {
        return new SshKeyPairGenerator(new DeterministicSecureRandom("pickle-ssh-fixture-seed"));
    }

    private static boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "-?").redirectErrorStream(true).start() != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static String runCommand(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    /**
     * A reproducible {@link SecureRandom}: a SHA-256 counter stream seeded from a
     * fixed label, so any {@code nextBytes} call pattern yields the same stream.
     */
    private static final class DeterministicSecureRandom extends SecureRandom {
        private final byte[] label;
        private long counter;

        DeterministicSecureRandom(String label) {
            this.label = label.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void nextBytes(byte[] bytes) {
            try {
                int filled = 0;
                while (filled < bytes.length) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    digest.update(label);
                    for (int i = 0; i < 8; i++) {
                        digest.update((byte) (counter >>> (i * 8)));
                    }
                    counter++;
                    byte[] block = digest.digest();
                    int take = Math.min(block.length, bytes.length - filled);
                    System.arraycopy(block, 0, bytes, filled, take);
                    filled += take;
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
