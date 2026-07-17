package kr.ac.pusan.pickle.common.crypto;

/**
 * A validated SSH public key (output of {@link SshPublicKeyParser}).
 *
 * @param algorithm      accepted key algorithm
 * @param normalizedLine the canonical {@code <type> <base64>} one-liner with any
 *                       trailing comment stripped — this is what we store and
 *                       re-display
 * @param fingerprint    OpenSSH SHA-256 fingerprint ({@code SHA256:<base64, no
 *                       padding>}), identical to {@code ssh-keygen -lf}
 */
public record ParsedSshKey(SshKeyAlgorithm algorithm, String normalizedLine, String fingerprint) {
}
