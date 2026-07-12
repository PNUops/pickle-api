package kr.ac.pusan.pickle.sshgw.dto;

/**
 * Route granted (docs/api/internal.md Link 1, HTTP 200). sshpiper pipes the
 * session to {@code ip:port} as {@code user}, passing the client's typed
 * password through to the VM's own sshd (v1 shared-password passthrough).
 */
public record RouteResponse(String ip, int port, String user) {
}
