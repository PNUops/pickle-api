package kr.ac.pusan.pickle.common.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates an Ed25519 key pair and serializes it to the OpenSSH formats:
 * the {@code ssh-ed25519 <base64>} public one-liner and the unencrypted
 * {@code openssh-key-v1} private-key PEM — implemented directly (no SSH
 * library) so the byte layout is auditable and matches {@code ssh-keygen}.
 *
 * <p>The {@link SecureRandom} is injected so a test can pin the key material
 * (fixed-seed fixtures) and cross-check the output against {@code ssh-keygen -y}
 * / {@code -lf}. Production uses a real {@code SecureRandom}.</p>
 */
@Component
public class SshKeyPairGenerator {

    private static final String KEY_TYPE = "ssh-ed25519";
    private static final byte[] OPENSSH_MAGIC =
            "openssh-key-v1\0".getBytes(StandardCharsets.UTF_8);
    /** The "none" cipher (unencrypted) blocks at 8 bytes for the padding. */
    private static final int NONE_CIPHER_BLOCK = 8;

    private final SecureRandom random;

    public SshKeyPairGenerator() {
        this(new SecureRandom());
    }

    /** Test seam: inject a deterministic RNG to pin the generated key. */
    public SshKeyPairGenerator(SecureRandom random) {
        this.random = random;
    }

    /**
     * Generates a new Ed25519 key pair.
     *
     * @param comment the comment embedded in the private-key block (the public
     *                one-liner is normalized without a comment)
     */
    public GeneratedSshKeyPair generate(String comment) {
        KeyPair keyPair = newEd25519KeyPair();
        byte[] publicKey = rawPublicKey(keyPair);
        byte[] seed = rawSeed(keyPair.getPrivate());

        byte[] publicBlob = publicKeyBlob(publicKey);
        String publicKeyLine = KEY_TYPE + " " + Base64.getEncoder().encodeToString(publicBlob);
        String privateKeyPem = privateKeyPem(publicBlob, publicKey, seed,
                comment == null ? "" : comment);
        return new GeneratedSshKeyPair(publicKeyLine, privateKeyPem);
    }

    private KeyPair newEd25519KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, random);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 key generation failed", e);
        }
    }

    /**
     * The 32-byte raw Ed25519 public key. It is the trailing 32 bytes of the
     * X.509 SubjectPublicKeyInfo encoding (fixed 12-byte prefix for Ed25519).
     */
    private static byte[] rawPublicKey(KeyPair keyPair) {
        byte[] spki = keyPair.getPublic().getEncoded();
        return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    /**
     * The 32-byte Ed25519 seed (the private scalar source). {@link
     * EdECPrivateKey#getBytes()} exposes it directly for a software key.
     */
    private static byte[] rawSeed(java.security.PrivateKey privateKey) {
        if (privateKey instanceof EdECPrivateKey edKey) {
            byte[] seed = edKey.getBytes().orElseThrow(() -> new IllegalStateException(
                    "Ed25519 private key exposes no seed bytes"));
            if (seed.length != 32) {
                throw new IllegalStateException("unexpected Ed25519 seed length " + seed.length);
            }
            return seed;
        }
        // Fallback: PKCS#8 wraps the 32-byte seed as its trailing octet string.
        byte[] pkcs8 = privateKey.getEncoded();
        return Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
    }

    /** {@code string "ssh-ed25519" || string <pub32>} — the SSH key blob. */
    private static byte[] publicKeyBlob(byte[] publicKey) {
        SshWriter writer = new SshWriter();
        writer.putString(KEY_TYPE.getBytes(StandardCharsets.UTF_8));
        writer.putString(publicKey);
        return writer.toByteArray();
    }

    private static String privateKeyPem(byte[] publicBlob, byte[] publicKey, byte[] seed,
            String comment) {
        // The unencrypted private section, padded to the cipher block size.
        SshWriter priv = new SshWriter();
        // Two identical checkints prove a correct decrypt. ssh-keygen uses a
        // random value; deriving it from the seed keeps a fixed-seed fixture
        // fully reproducible without affecting -y/-lf (which ignore it).
        byte[] checkBytes = Arrays.copyOfRange(seed, 0, 4);
        priv.putRaw(checkBytes);
        priv.putRaw(checkBytes);
        priv.putString(KEY_TYPE.getBytes(StandardCharsets.UTF_8));
        priv.putString(publicKey);
        byte[] privateKey64 = new byte[64];
        System.arraycopy(seed, 0, privateKey64, 0, 32);
        System.arraycopy(publicKey, 0, privateKey64, 32, 32);
        priv.putString(privateKey64);
        priv.putString(comment.getBytes(StandardCharsets.UTF_8));
        pad(priv);

        SshWriter outer = new SshWriter();
        outer.putRaw(OPENSSH_MAGIC);
        outer.putString("none".getBytes(StandardCharsets.UTF_8));   // ciphername
        outer.putString("none".getBytes(StandardCharsets.UTF_8));   // kdfname
        outer.putString(new byte[0]);                               // kdfoptions
        outer.putUint32(1);                                         // key count
        outer.putString(publicBlob);
        outer.putString(priv.toByteArray());

        String body = Base64.getMimeEncoder(70, new byte[] {'\n'})
                .encodeToString(outer.toByteArray());
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                + body + "\n"
                + "-----END OPENSSH PRIVATE KEY-----\n";
    }

    /** Appends 1,2,3… padding so the section length is a block multiple. */
    private static void pad(SshWriter writer) {
        int remainder = writer.size() % NONE_CIPHER_BLOCK;
        if (remainder != 0) {
            int padLength = NONE_CIPHER_BLOCK - remainder;
            byte[] padding = new byte[padLength];
            for (int i = 0; i < padLength; i++) {
                padding[i] = (byte) (i + 1);
            }
            writer.putRaw(padding);
        }
    }

    /** Minimal big-endian SSH wire writer. */
    private static final class SshWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void putUint32(int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        void putString(byte[] bytes) {
            putUint32(bytes.length);
            out.writeBytes(bytes);
        }

        void putRaw(byte[] bytes) {
            out.writeBytes(bytes);
        }

        int size() {
            return out.size();
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
