package kr.ac.pusan.pickle.llm.openrouter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;
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
 * Client for OpenRouter's key-management API. One pickle key with a money
 * budget owns exactly one OpenRouter runtime key; the money limit is enforced
 * by OpenRouter, which is the whole design — pickle's own accounting arrives
 * a batch late, and an overshoot in money is a bill.
 *
 * <p>Two kinds of credential move through here and neither may ever reach a
 * log or an exception: the management bearer this client presents, and the
 * one-time runtime key plaintext a create returns. Errors carry status codes
 * and OpenRouter's message text only.</p>
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Pagination backstop for {@link #listKeys()} — see the loop's comment. */
    private static final int MAX_KEY_PAGES = 200;

    private final OpenRouterProperties properties;
    private final RestClient restClient;

    public OpenRouterClient(OpenRouterProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** Whether a management key is configured at all. */
    public boolean configured() {
        return properties.configured();
    }

    /**
     * One managed key as the list/read endpoints describe it. {@code limit}
     * and {@code limitReset} are null when OpenRouter has none set.
     *
     * <p>{@code usage} is what this key has spent, as OpenRouter counts it.
     * The money limit is enforced there rather than here, so their figure is
     * the authority: ours would be one shipped batch behind and carries no
     * prices at all. Null when the listing does not report it.
     */
    public record ManagedKey(String hash, String name, boolean disabled,
            @Nullable BigDecimal limit, @Nullable String limitReset,
            boolean includeByokInLimit, @Nullable BigDecimal usage) {
    }

    /** A freshly created key: the identifier and the one-time plaintext. */
    public record CreatedKey(String hash, String plaintext) {
    }

    /**
     * {@code POST /keys}. The name is the pickle key's public id, which is
     * what makes the OpenRouter console row traceable back to the console.
     * The expiry mirrors the pickle key's, so a key that lapses here lapses
     * there too without anyone sweeping.
     */
    public CreatedKey createKey(String name, BigDecimal limit,
            @Nullable CreditLimitReset reset, @Nullable Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("limit", limit);
        body.put("include_byok_in_limit", true);
        if (reset != null) {
            body.put("limit_reset", reset.wireValue());
        }
        if (expiresAt != null) {
            body.put("expires_at", expiresAt.toString());
        }
        JsonNode node = exchange(HttpMethod.POST, "/keys", body, 200, 201);
        String plaintext = text(node, "key");
        String hash = text(node.path("data"), "hash");
        if (plaintext == null || hash == null) {
            throw new OpenRouterException(0,
                    "create answered without a key or a hash (fields absent)");
        }
        return new CreatedKey(hash, plaintext);
    }

    /** {@code PATCH /keys/{hash}} — flip the disabled flag. */
    public void setDisabled(String hash, boolean disabled) {
        exchange(HttpMethod.PATCH, "/keys/" + hash, Map.of("disabled", disabled), 200);
    }

    /**
     * {@code PATCH /keys/{hash}} — move the money limit and its window, and
     * assert that BYOK inference counts against that limit.
     *
     * <p>The flag rides every limit write rather than being set once at
     * creation, because it is the reconciler's only way to repair a key that
     * predates it or that someone flipped in the OpenRouter console. It
     * defaults to false over there, and a false value means the limit stops
     * bounding anything the moment a provider key is attached to the account:
     * the ceiling still shows in our console and governs nothing.
     */
    public void updateLimit(String hash, BigDecimal limit, @Nullable CreditLimitReset reset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        body.put("limit_reset", reset == null ? null : reset.wireValue());
        body.put("include_byok_in_limit", true);
        exchange(HttpMethod.PATCH, "/keys/" + hash, body, 200);
    }

    /** {@code DELETE /keys/{hash}}. A 404 counts as done — it is already gone. */
    public void deleteKey(String hash) {
        try {
            exchange(HttpMethod.DELETE, "/keys/" + hash, null, 200, 204);
        } catch (OpenRouterException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }

    /**
     * {@code GET /keys} — every managed key, walking the offset pagination to
     * the end. The reconciler's raw material: what OpenRouter believes exists.
     */
    public List<ManagedKey> listKeys() {
        List<ManagedKey> keys = new ArrayList<>();
        int offset = 0;
        // Bounded: an upstream that ignores `offset` (or keeps answering with
        // a full page) would otherwise loop forever inside a job worker,
        // growing the list until the process dies. The cap is far above any
        // real key count; hitting it is a malfunction, and it is reported as
        // one rather than silently truncating the reconciler's view.
        for (int page = 0; page < MAX_KEY_PAGES; page++) {
            JsonNode node = exchange(HttpMethod.GET, "/keys?include_disabled=true&offset=" + offset,
                    null, 200);
            JsonNode data = node.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return keys;
            }
            for (JsonNode entry : data) {
                String hash = text(entry, "hash");
                if (hash == null) {
                    continue;
                }
                keys.add(new ManagedKey(hash, text(entry, "name"),
                        entry.path("disabled").asBoolean(false),
                        entry.path("limit").isNumber()
                                ? entry.path("limit").decimalValue() : null,
                        text(entry, "limit_reset"),
                        entry.path("include_byok_in_limit").asBoolean(false),
                        entry.path("usage").isNumber()
                                ? entry.path("usage").decimalValue() : null));
            }
            offset += data.size();
        }
        throw new OpenRouterException(0, "key listing did not end within "
                + MAX_KEY_PAGES + " pages; refusing a partial view");
    }

    private JsonNode exchange(HttpMethod method, String path, @Nullable Object body,
            int... acceptable) {
        RestClient.RequestBodySpec spec = restClient.method(method)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = (RestClient.RequestBodySpec) spec
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(body));
        }
        try {
            return spec.exchange((req, response) -> {
                String responseBody = readBody(response.getBody());
                int status = response.getStatusCode().value();
                for (int ok : acceptable) {
                    if (status == ok) {
                        return tree(responseBody);
                    }
                }
                throw new OpenRouterException(status, errorText(responseBody, status));
            });
        } catch (ResourceAccessException e) {
            throw new OpenRouterException(0, "transport: " + e.getMessage());
        }
    }

    private String authorizationHeader() {
        if (!properties.configured()) {
            throw new IllegalStateException("OpenRouter management key is not configured: set "
                    + "PICKLE_OPENROUTER_MGMT_KEY (pickle.openrouter.management-key)");
        }
        return "Bearer " + properties.managementKey();
    }

    /** OpenRouter's error message, bounded; never the request we sent. */
    private static String errorText(String body, int status) {
        JsonNode node = tree(body);
        String message = text(node.path("error"), "message");
        if (message == null) {
            message = "HTTP " + status;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
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
}
