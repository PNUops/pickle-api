package kr.ac.pusan.pickle.vm.dto;

/**
 * Contract schema {@code InitialPasswordResponse} — the one-shot plaintext.
 * {@code sshHost}/{@code sshPort} are advisory connection hints (null until
 * the SSH gateway lands, M4).
 */
public record InitialPasswordResponse(
        String password,
        String sshUsername,
        String sshHost,
        Integer sshPort) {
}
