package kr.ac.pusan.pickle.common.crypto;

/**
 * A server-generated SSH key pair (output of {@link SshKeyPairGenerator}).
 *
 * @param publicKeyLine  the normalized {@code ssh-ed25519 <base64>} one-liner
 *                       (no comment) — registered like a pasted public key
 * @param privateKeyPem  the unencrypted OpenSSH ({@code openssh-key-v1}) PEM —
 *                       returned to the user for download, never logged
 */
public record GeneratedSshKeyPair(String publicKeyLine, String privateKeyPem) {
}
