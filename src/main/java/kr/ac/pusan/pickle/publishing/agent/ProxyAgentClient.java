package kr.ac.pusan.pickle.publishing.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.ProxyAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the proxy-agent reverse-proxy control link (the proxy-agent
 * control contract). pickle-api pushes desired routing state; the Go agent renders nginx
 * and reports back. Truth is the DB, nginx config is derived.
 *
 * <p>Fail-closed: the shared bearer token has no default outside dev/test — an
 * unset token throws on first use instead of sending an empty bearer. The agent
 * additionally checks the source IP (172.30.1.20) and the token; this client
 * only speaks the JSON contract.</p>
 */
@Component
public class ProxyAgentClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyAgentClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProxyAgentProperties properties;
    private final RestClient restClient;

    public ProxyAgentClient(ProxyAgentProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * {@code POST /apply} — full desired state for one FQDN. 200 → APPLIED,
     * 409 → STALE (superseded no-op), 422 → FAILED (with nginx stderr).
     */
    public ApplyOutcome apply(ApplyRequest request) {
        return exchange("/apply", request);
    }

    /**
     * {@code POST /sync-all} — authoritative full-manifest reconciliation. 200 →
     * APPLIED, 409 → STALE snapshot, 422 → FAILED (nothing changed).
     */
    public ApplyOutcome syncAll(long snapshotGeneration, List<ApplyRequest> routes) {
        return exchange("/sync-all", new SyncAllRequest(snapshotGeneration, routes));
    }

    /**
     * {@code GET /status} — agent health, applied generations, and cert-issuance
     * results. The ONLY place certbot failures surface (an {@code /apply} is 200
     * even when issuance failed). Empty on transport failure or a non-200.
     */
    public Optional<AgentStatus> status() {
        try {
            return restClient.get()
                    .uri("/status")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((req, response) -> {
                        String responseBody = readBody(response.getBody());
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            log.warn("proxy-agent GET /status HTTP {}: {}", status, responseBody);
                            return Optional.<AgentStatus>empty();
                        }
                        return Optional.of(parseStatus(responseBody));
                    });
        } catch (ResourceAccessException e) {
            log.warn("proxy-agent transport failure on GET /status: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static AgentStatus parseStatus(String body) {
        JsonNode node = tree(body);
        List<AgentStatus.RouteState> routes = new ArrayList<>();
        for (JsonNode route : node.path("routes")) {
            routes.add(new AgentStatus.RouteState(text(route, "fqdn"),
                    route.path("present").asBoolean(false),
                    route.path("generation").isNumber() ? route.path("generation").asLong() : null));
        }
        List<AgentStatus.CertState> certs = new ArrayList<>();
        for (JsonNode cert : node.path("certs")) {
            certs.add(new AgentStatus.CertState(text(cert, "fqdn"), certState(text(cert, "state")),
                    instant(text(cert, "checkedAt")), text(cert, "error")));
        }
        return new AgentStatus(List.copyOf(routes), List.copyOf(certs));
    }

    /** Unknown/absent states read as PENDING — never a false OK. */
    private static AgentStatus.CertState.State certState(String value) {
        try {
            return value != null ? AgentStatus.CertState.State.valueOf(value)
                    : AgentStatus.CertState.State.PENDING;
        } catch (IllegalArgumentException unknownState) {
            return AgentStatus.CertState.State.PENDING;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static Instant instant(String value) {
        try {
            return value != null ? Instant.parse(value) : null;
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    private ApplyOutcome exchange(String path, Object body) {
        String json = JSON.writeValueAsString(body);
        try {
            return restClient.method(HttpMethod.POST)
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((req, response) -> {
                        String responseBody = readBody(response.getBody());
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 200 -> ApplyOutcome.applied(readGeneration(responseBody));
                            case 409 -> ApplyOutcome.stale(readGeneration(responseBody));
                            case 422 -> ApplyOutcome.failed(readError(responseBody));
                            default -> ApplyOutcome.failed(
                                    "proxy-agent HTTP " + status + ": " + responseBody);
                        };
                    });
        } catch (ResourceAccessException e) {
            // Connection refused/reset, timeout … — no HTTP response. Distinct
            // from a 422 FAILED: the config was never judged, so callers may
            // retry (JobRunr transport retry + the recurring route reconciler).
            log.warn("proxy-agent transport failure on POST {}: {}", path, e.getMessage());
            return ApplyOutcome.transport("proxy-agent 연결 실패: " + e.getMessage());
        }
    }

    private String authorizationHeader() {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new IllegalStateException("proxy-agent token is not configured: set "
                    + "PICKLE_PROXY_AGENT_TOKEN (pickle.proxy-agent.token)");
        }
        return "Bearer " + properties.token();
    }

    private static Long readGeneration(String body) {
        JsonNode node = tree(body);
        JsonNode generation = node.path("generation");
        if (!generation.isNumber()) {
            generation = node.path("snapshotGeneration"); // /sync-all response shape
        }
        return generation.isNumber() ? generation.asLong() : null;
    }

    private static String readError(String body) {
        JsonNode node = tree(body);
        JsonNode error = node.path("error");
        return error.isString() ? error.asString() : body;
    }

    private static JsonNode tree(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    private static String readBody(InputStream in) {
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** {@code POST /sync-all} body (the proxy-agent control contract). */
    private record SyncAllRequest(long snapshotGeneration, List<ApplyRequest> routes) {
    }
}
