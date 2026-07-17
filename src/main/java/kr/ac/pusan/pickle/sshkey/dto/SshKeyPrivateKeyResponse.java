package kr.ac.pusan.pickle.sshkey.dto;

/**
 * Contract schema {@code SshKeyPrivateKeyResponse} — the OpenSSH private PEM of
 * a server-generated key, returned on download (never logged, never cached).
 */
public record SshKeyPrivateKeyResponse(String privateKey, String fileName) {
}
