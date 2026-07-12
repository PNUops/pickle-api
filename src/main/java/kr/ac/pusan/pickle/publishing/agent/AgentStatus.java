package kr.ac.pusan.pickle.publishing.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The subset of the proxy-agent {@code GET /status} response pickle-api
 * consumes (docs/api/internal.md Link 2): per-FQDN applied route state and
 * cert-issuance results. The agent surfaces certbot failures ONLY here — an
 * {@code /apply} answers 200 even when issuance failed.
 */
public record AgentStatus(List<RouteState> routes, List<CertState> certs) {

    /** One agent-managed vhost and its applied generation. */
    public record RouteState(String fqdn, boolean present, Long generation) {
    }

    /** Issuance/renewal state of one custom domain's LE certificate. */
    public record CertState(String fqdn, State state, Instant checkedAt, String error) {

        public enum State {
            OK,
            PENDING,
            FAILED
        }
    }

    /** The cert entry for an FQDN, when the agent tracks one. */
    public Optional<CertState> cert(String fqdn) {
        return certs.stream().filter(cert -> fqdn.equals(cert.fqdn())).findFirst();
    }
}
