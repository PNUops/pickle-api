package kr.ac.pusan.pickle.common.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Self-contained parser/validator for OpenSSH public keys — no external SSH
 * library, so the accept-list is a deliberate, auditable whitelist. It parses
 * the {@code authorized_keys} one-line form ({@code <type> <base64> [comment]}),
 * verifies the declared type against the type embedded in the key blob, enforces
 * an RSA modulus floor, and computes the canonical SHA-256 fingerprint.
 *
 * <p>Rejections throw {@link SshPublicKeyParseException} with a Korean,
 * user-facing message; the registration service maps it to a 422 field error.
 * Nothing here logs the key material.</p>
 */
@Component
public class SshPublicKeyParser {

    /** Guards against a decompression-bomb-style oversized blob. */
    private static final int MAX_BLOB_BYTES = 16 * 1024;
    /** OpenSSH's own minimum for freshly generated RSA keys. */
    private static final int MIN_RSA_MODULUS_BITS = 2048;

    /**
     * Parses and validates one OpenSSH public-key line.
     *
     * @throws SshPublicKeyParseException on any malformed / unaccepted input
     */
    public ParsedSshKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SshPublicKeyParseException("공개키를 입력해 주세요.");
        }
        // authorized_keys form: type, base64 body, optional free-form comment.
        // Split on any run of whitespace; take at most three fields.
        String[] fields = raw.strip().split("\\s+", 3);
        if (fields.length < 2) {
            throw new SshPublicKeyParseException(
                    "공개키 형식이 올바르지 않습니다. `<type> <base64>` 한 줄이어야 합니다.");
        }
        String declaredType = fields[0];
        SshKeyAlgorithm algorithm = SshKeyAlgorithm.fromWireType(declaredType);
        if (algorithm == null) {
            throw new SshPublicKeyParseException(
                    "지원하지 않는 키 형식입니다. ed25519 키를 권장합니다 (ssh-keygen -t ed25519).");
        }

        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(fields[1]);
        } catch (IllegalArgumentException e) {
            throw new SshPublicKeyParseException("공개키 본문(base64)을 해독할 수 없습니다.");
        }
        if (blob.length == 0 || blob.length > MAX_BLOB_BYTES) {
            throw new SshPublicKeyParseException("공개키 본문의 크기가 올바르지 않습니다.");
        }

        BlobReader reader = new BlobReader(blob);
        String embeddedType = reader.readString();
        if (!declaredType.equals(embeddedType)) {
            // A key whose declared type disagrees with its blob is malformed or
            // spoofed — refuse rather than trust the label.
            throw new SshPublicKeyParseException("공개키의 선언 타입과 실제 내용이 일치하지 않습니다.");
        }
        if (algorithm == SshKeyAlgorithm.RSA) {
            reader.readMpint();                       // public exponent e (unused)
            BigInteger modulus = reader.readMpint();  // modulus n
            if (modulus.bitLength() < MIN_RSA_MODULUS_BITS) {
                throw new SshPublicKeyParseException(
                        "RSA 키는 2048비트 이상이어야 합니다. ed25519 키를 권장합니다.");
            }
        }

        String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(sha256(blob));
        // Normalize to `<type> <base64>` — comment dropped, exact key bytes kept.
        String normalizedLine = declaredType + " " + fields[1];
        return new ParsedSshKey(algorithm, normalizedLine, fingerprint);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Cursor over the SSH wire format: length-prefixed strings and mpints. */
    private static final class BlobReader {
        private final byte[] data;
        private int offset;

        BlobReader(byte[] data) {
            this.data = data;
        }

        /** Reads a {@code uint32}-prefixed byte string as UTF-8 text. */
        String readString() {
            return new String(readLengthPrefixed(), StandardCharsets.UTF_8);
        }

        /** Reads a {@code uint32}-prefixed mpint (two's-complement big integer). */
        BigInteger readMpint() {
            byte[] bytes = readLengthPrefixed();
            return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes);
        }

        private byte[] readLengthPrefixed() {
            if (offset + 4 > data.length) {
                throw new SshPublicKeyParseException("공개키 본문이 손상되었습니다.");
            }
            long length = ((data[offset] & 0xFFL) << 24)
                    | ((data[offset + 1] & 0xFFL) << 16)
                    | ((data[offset + 2] & 0xFFL) << 8)
                    | (data[offset + 3] & 0xFFL);
            offset += 4;
            if (length < 0 || offset + length > data.length) {
                throw new SshPublicKeyParseException("공개키 본문이 손상되었습니다.");
            }
            byte[] out = new byte[(int) length];
            System.arraycopy(data, offset, out, 0, (int) length);
            offset += (int) length;
            return out;
        }
    }
}
