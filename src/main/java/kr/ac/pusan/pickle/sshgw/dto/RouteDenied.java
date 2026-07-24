package kr.ac.pusan.pickle.sshgw.dto;

/**
 * Route denied (the internal SSH gateway route contract, HTTP 403/404). Carries only the
 * machine-readable {@code reason} code the sshgw plugin reads — no route, and
 * no user-facing prose (this is an infra-to-infra contract, not a browser one).
 */
public record RouteDenied(String reason) {
}
