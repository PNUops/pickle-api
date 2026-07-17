package kr.ac.pusan.pickle.common.crypto;

/**
 * Accepted SSH public-key algorithms (contract schema {@code SshKeyAlgorithm}).
 * The enum name is the console-facing value; {@link #wireType()} is the OpenSSH
 * key-type token that appears on the wire and in the stored public-key line.
 * Widening the accept-list is a minor contract revision.
 */
public enum SshKeyAlgorithm {
    ED25519("ssh-ed25519"),
    RSA("ssh-rsa");

    private final String wireType;

    SshKeyAlgorithm(String wireType) {
        this.wireType = wireType;
    }

    public String wireType() {
        return wireType;
    }

    /** The algorithm for an OpenSSH key-type token, or null if unaccepted. */
    public static SshKeyAlgorithm fromWireType(String wireType) {
        for (SshKeyAlgorithm algorithm : values()) {
            if (algorithm.wireType.equals(wireType)) {
                return algorithm;
            }
        }
        return null;
    }
}
