package kr.ac.pusan.pickle.sshgw.dto;

import java.util.List;

/**
 * Route granted (the internal SSH gateway route contract, HTTP 200). sshpiper pipes the
 * session to {@code ip:port} as {@code user}.
 *
 * <p>{@code hostKeys} are the VM's pinned host public keys ({@code
 * authorized_keys} one-line form); the gateway <b>must</b> verify the upstream
 * host key against this set (no {@code IgnoreHostKey}) and treats an empty array
 * as fail-closed. On the publickey path the gateway authenticates upstream with
 * its platform key; on the password path it passes the client's typed password
 * through (opt-in VMs only).</p>
 */
public record RouteResponse(String ip, int port, String user, List<String> hostKeys) {
}
