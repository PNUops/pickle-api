package kr.ac.pusan.pickle.terminal.dto;

import java.util.List;

/**
 * Ticket redeem granted (the internal web-terminal contract, HTTP 200). The bridge
 * opens SSH to {@code vmIp:port} as {@code user}, pinning the upstream host key
 * against {@code hostKeys}.
 *
 * <p>{@code hostKeys} are all collected host public keys of the VM
 * ({@code vms.ssh_host_key}, newline-joined multi-type — the per-user host-key rule). An
 * <b>empty</b> array is returned as-is (200): the bridge treats an empty/mismatched
 * pin set as fail-closed and refuses with WS 4006, so the deny path is owned by
 * the bridge, not by an extra api reason code.</p>
 */
public record TerminalRedeemResponse(String sessionId, long userId, long vmId, String vmIp,
        int port, String user, List<String> hostKeys) {
}
