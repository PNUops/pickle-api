package kr.ac.pusan.pickle.common.crypto;

/**
 * Thrown by {@link SshPublicKeyParser} when a public key is malformed or of an
 * unaccepted type. The message is a Korean, user-facing field explanation the
 * registration service surfaces as a 422 {@code VALIDATION_FAILED} error.
 */
public class SshPublicKeyParseException extends RuntimeException {

    public SshPublicKeyParseException(String message) {
        super(message);
    }
}
