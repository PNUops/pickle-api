package kr.ac.pusan.pickle.llm.openrouter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * one-time runtime key plaintext a create returns. Errors carry only a status
 * code and locally generated text; vendor response bodies are discarded.</p>
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Pagination backstop for the key listing — see the loop's comment. */
    private static final int MAX_KEY_PAGES = 200;

    private final RestClient restClient;

    public OpenRouterClient(OpenRouterProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
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
            boolean includeByokInLimit, @Nullable BigDecimal usage,
            @Nullable UUID workspaceId, @Nullable BigDecimal limitRemaining) {

        public ManagedKey(String hash, String name, boolean disabled,
                @Nullable BigDecimal limit, @Nullable String limitReset,
                boolean includeByokInLimit, @Nullable BigDecimal usage) {
            this(hash, name, disabled, limit, limitReset, includeByokInLimit, usage, null, null);
        }

        public ManagedKey(String hash, String name, boolean disabled,
                @Nullable BigDecimal limit, @Nullable String limitReset,
                boolean includeByokInLimit, @Nullable BigDecimal usage,
                @Nullable UUID workspaceId) {
            this(hash, name, disabled, limit, limitReset, includeByokInLimit, usage,
                    workspaceId, null);
        }
    }

    /** A freshly created key: the identifier and the one-time plaintext. */
    public record CreatedKey(String hash, String plaintext, @Nullable UUID workspaceId) {

        public CreatedKey(String hash, String plaintext) {
            this(hash, plaintext, null);
        }
    }

    /** Account-wide totals reported by the vendor credit meter. */
    public record Credits(BigDecimal totalCredits, BigDecimal totalUsage) {
    }

    /**
     * One row of the vendor's public model catalogue.
     *
     * <p>Prices are per token as the vendor states them, kept as {@link
     * BigDecimal} rather than scaled here: the difference between the cheapest
     * and dearest model is four orders of magnitude, and the reason an approver
     * is shown this list at all is to see that difference.
     */
    public record VendorModel(String id, String name, @Nullable Integer contextLength,
            @Nullable BigDecimal promptPrice, @Nullable BigDecimal completionPrice) {
    }

    /**
     * {@code POST /keys} under one account's management credential. The name
     * is the pickle key's public id, which is what makes the OpenRouter
     * console row traceable back to the console. The expiry mirrors the
     * pickle key's, so a key that lapses here lapses there too without
     * anyone sweeping.
     */
    public CreatedKey createKey(String managementSecret, @Nullable UUID workspaceId,
            String name, BigDecimal limit, @Nullable CreditLimitReset reset,
            @Nullable Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("limit", limit);
        body.put("include_byok_in_limit", true);
        if (workspaceId != null) {
            body.put("workspace_id", workspaceId.toString());
        }
        if (reset != null) {
            body.put("limit_reset", reset.wireValue());
        }
        if (expiresAt != null) {
            body.put("expires_at", expiresAt.toString());
        }
        JsonNode node = exchange(managementSecret, HttpMethod.POST, "/keys", body, 200, 201);
        String plaintext = text(node, "key");
        String hash = text(node.path("data"), "hash");
        if (plaintext == null || hash == null) {
            throw new OpenRouterException(0,
                    "create answered without a key or a hash (fields absent)");
        }
        return new CreatedKey(hash, plaintext, firstUuid(node.path("data"), node,
                "workspace_id"));
    }

    /** {@code PATCH /keys/{hash}} — flip the disabled flag. */
    public void setDisabled(String managementSecret, @Nullable UUID workspaceId,
            String hash, boolean disabled) {
        exchange(managementSecret, HttpMethod.PATCH, keyPath(hash),
                Map.of("disabled", disabled), 200);
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
    public void updateLimit(String managementSecret, @Nullable UUID workspaceId,
            String hash, BigDecimal limit, @Nullable CreditLimitReset reset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        body.put("limit_reset", reset == null ? null : reset.wireValue());
        body.put("include_byok_in_limit", true);
        exchange(managementSecret, HttpMethod.PATCH, keyPath(hash), body, 200);
    }

    /** {@code DELETE /keys/{hash}}. A 404 counts as done — it is already gone. */
    public void deleteKey(String managementSecret, @Nullable UUID workspaceId, String hash) {
        try {
            exchange(managementSecret, HttpMethod.DELETE, keyPath(hash),
                    null, 200, 204);
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
    public List<ManagedKey> listKeys(String managementSecret, @Nullable UUID workspaceId) {
        List<ManagedKey> keys = new ArrayList<>();
        int offset = 0;
        // Bounded: an upstream that ignores `offset` (or keeps answering with
        // a full page) would otherwise loop forever inside a job worker,
        // growing the list until the process dies. The cap is far above any
        // real key count; hitting it is a malfunction, and it is reported as
        // one rather than silently truncating the reconciler's view.
        for (int page = 0; page < MAX_KEY_PAGES; page++) {
            String path = "/keys?include_disabled=true&offset=" + offset
                    + workspaceQuery(workspaceId, true);
            JsonNode node = exchange(managementSecret, HttpMethod.GET, path, null, 200);
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
                                ? entry.path("usage").decimalValue() : null,
                        uuid(entry, "workspace_id"),
                        entry.path("limit_remaining").isNumber()
                                ? entry.path("limit_remaining").decimalValue() : null));
            }
            offset += data.size();
        }
        throw new OpenRouterException(0, "key listing did not end within "
                + MAX_KEY_PAGES + " pages; refusing a partial view");
    }

    public ManagedKey getKey(String managementSecret, @Nullable UUID workspaceId, String hash) {
        JsonNode node = exchange(managementSecret, HttpMethod.GET, keyPath(hash),
                null, 200);
        JsonNode data = node.path("data");
        String returnedHash = text(data, "hash");
        if (returnedHash == null) {
            throw new OpenRouterException(0, "key read answered without a hash");
        }
        UUID returnedWorkspace = uuid(data, "workspace_id");
        if (workspaceId != null && !workspaceId.equals(returnedWorkspace)) {
            throw new OpenRouterException(0, "key belongs to a different workspace");
        }
        return new ManagedKey(returnedHash, text(data, "name"),
                data.path("disabled").asBoolean(false),
                data.path("limit").isNumber() ? data.path("limit").decimalValue() : null,
                text(data, "limit_reset"),
                data.path("include_byok_in_limit").asBoolean(false),
                data.path("usage").isNumber() ? data.path("usage").decimalValue() : null,
                returnedWorkspace,
                data.path("limit_remaining").isNumber()
                        ? data.path("limit_remaining").decimalValue() : null);
    }

    public Credits credits(String managementSecret) {
        JsonNode node = exchange(managementSecret, HttpMethod.GET, "/credits", null, 200);
        JsonNode data = node.path("data");
        if (!data.path("total_credits").isNumber() || !data.path("total_usage").isNumber()) {
            throw new OpenRouterException(0, "credits answered without account totals");
        }
        return new Credits(data.path("total_credits").decimalValue(),
                data.path("total_usage").decimalValue());
    }

    /**
     * {@code GET /models}, the vendor's public catalogue.
     *
     * <p>The only call here that carries no credential, and deliberately so.
     * This list is the same for everybody, so borrowing an account's management
     * secret would tie a global catalogue to one tenant's credential health:
     * that account lapsing would empty the list for every institution. The
     * vendor serves it unauthenticated with {@code max-age=300}.
     *
     * <p>A row with no usable id is skipped rather than failing the fetch — one
     * malformed entry in four hundred should cost that entry, not the refresh.
     * An empty or unparseable document is a different matter and throws, because
     * "the vendor returned nothing" must not be storable as "the vendor has no
     * models".
     */
    public List<VendorModel> catalogue() {
        JsonNode node = exchangeUnauthenticated(HttpMethod.GET, "/models", 200);
        JsonNode data = node.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new OpenRouterException(0, "model catalogue answered without any models");
        }
        List<VendorModel> models = new ArrayList<>(data.size());
        for (JsonNode entry : data) {
            String id = text(entry, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            String name = text(entry, "name");
            JsonNode pricing = entry.path("pricing");
            models.add(new VendorModel(id.trim(), name == null ? id.trim() : name,
                    entry.path("context_length").isNumber()
                            ? entry.path("context_length").asInt() : null,
                    price(pricing, "prompt"), price(pricing, "completion")));
        }
        if (models.isEmpty()) {
            throw new OpenRouterException(0, "model catalogue answered without any usable models");
        }
        // The vendor states how many models it has. Today the whole set arrives
        // in one response and `links.next` is null, but reading only page one
        // would not merely store a short list: replaceListing treats anything
        // absent as delisted, so a truncation switches those models off. This is
        // the posture listKeys already takes toward a partial view.
        JsonNode total = node.path("total_count");
        if (total.isNumber() && total.asInt() != data.size()) {
            throw new OpenRouterException(0, "model catalogue answered a partial page");
        }
        return List.copyOf(models);
    }

    /**
     * Prices arrive as decimal strings, not numbers, and the field carries
     * three meanings rather than two.
     *
     * <p>A number is a price and <b>zero is a real one</b> — the vendor's free
     * tier — so it must survive. Missing or unparseable is unknown. And
     * <b>negative is the vendor's sentinel for "priced by whatever model this
     * routes to"</b>: the router entries ({@code openrouter/auto} and its
     * siblings, five of 427 when this was written) all publish {@code "-1"}.
     * That is not a price and must not be stored as one. It parses cleanly, so
     * it reached the column's non-negative CHECK and would have failed every
     * refresh against a listing this complete.
     */
    private static @Nullable BigDecimal price(JsonNode pricing, String field) {
        JsonNode value = pricing.path(field);
        String raw = value.isString() ? value.asString() : null;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode exchangeUnauthenticated(HttpMethod method, String path, int... acceptable) {
        try {
            return restClient.method(method).uri(path).accept(MediaType.APPLICATION_JSON)
                    .exchange((req, response) -> {
                        String responseBody = readBody(response.getBody());
                        int status = response.getStatusCode().value();
                        for (int ok : acceptable) {
                            if (status == ok) {
                                return tree(responseBody);
                            }
                        }
                        // Not errorText: that says "management request", and
                        // this is the one call in the class that carries no
                        // management credential.
                        throw new OpenRouterException(status,
                                "public request rejected with HTTP " + status);
                    });
        } catch (ResourceAccessException e) {
            throw new OpenRouterException(0, "transport failure");
        }
    }

    private JsonNode exchange(String managementSecret, HttpMethod method, String path,
            @Nullable Object body,
            int... acceptable) {
        RestClient.RequestBodySpec spec = restClient.method(method)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader(managementSecret))
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
                throw new OpenRouterException(status, errorText(status));
            });
        } catch (ResourceAccessException e) {
            throw new OpenRouterException(0, "transport failure");
        }
    }

    private static String authorizationHeader(String managementSecret) {
        if (managementSecret == null || managementSecret.isBlank()) {
            throw new IllegalStateException("OpenRouter management credential is unavailable");
        }
        return "Bearer " + managementSecret;
    }

    /** Vendor response bodies are deliberately not propagated or logged. */
    private static String errorText(int status) {
        return "management request rejected with HTTP " + status;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static @Nullable UUID firstUuid(JsonNode primary, JsonNode secondary, String field) {
        UUID value = uuid(primary, field);
        return value != null ? value : uuid(secondary, field);
    }

    private static String keyPath(String hash) {
        return "/keys/" + encode(hash);
    }

    private static String workspaceQuery(@Nullable UUID workspaceId, boolean append) {
        if (workspaceId == null) {
            return "";
        }
        return (append ? "&" : "?") + "workspace_id=" + encode(workspaceId.toString());
    }

    private static @Nullable UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new OpenRouterException(0, "vendor returned an invalid workspace id");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
