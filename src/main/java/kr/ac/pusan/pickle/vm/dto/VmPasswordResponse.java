package kr.ac.pusan.pickle.vm.dto;

/**
 * Contract schema {@code VmPasswordResponse} (v0.8.0 rename of
 * {@code InitialPasswordResponse}) — the VM guest-account password plus SSH
 * connection hints. {@code sshHost}/{@code sshPort} come from server config
 * (the SSH gateway address); the plaintext must not be cached or stored.
 */
public record VmPasswordResponse(
        String password,
        String sshUsername,
        String sshHost,
        Integer sshPort) {
}
