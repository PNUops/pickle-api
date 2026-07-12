package kr.ac.pusan.pickle.publishing.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Client for the proxy-agent reverse-proxy control link (docs/api/internal.md
 * Link 2). pickle-api pushes desired routing state; the Go agent renders nginx
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
            // Connection refused/reset, timeout … — no HTTP response. Surface as a
            // FAILED outcome so the job records it on the route (retryable).
            log.warn("proxy-agent transport failure on POST {}: {}", path, e.getMessage());
            return ApplyOutcome.failed("proxy-agent 연결 실패: " + e.getMessage());
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

    /** {@code POST /sync-all} body (docs/api/internal.md Link 2). */
    private record SyncAllRequest(long snapshotGeneration, List<ApplyRequest> routes) {
    }
}
