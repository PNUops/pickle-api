package kr.ac.pusan.pickle.publishing.dns;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Cloud DNS through its REST API (v1 {@code resourceRecordSets}), a
 * thin client rather than the vendor SDK: the lifecycle needs four calls on
 * one resource, the token exchange is one HTTP round trip signed with the
 * jjwt already on the classpath, and the SDK would bring its own HTTP stack,
 * JSON library and auth chain into the dependency audit for that.
 *
 * <p>Per-record-set endpoints
 * ({@code GET/POST/PATCH/DELETE rrsets/{name}/{type}}) are used instead of the
 * older {@code changes} batch because they make the idempotence the contract
 * promises trivial: an ensure reads the set and patches only when it differs,
 * a remove treats 404 as done.</p>
 */
public class GoogleCloudDnsRecordProvider implements DnsRecordProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleCloudDnsRecordProvider.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Production API root; tests point the constructor at WireMock instead. */
    public static final String DEFAULT_API_BASE = "https://dns.googleapis.com/dns/v1";

    private static final int LIST_PAGE_SIZE = 500;

    private final RestClient restClient;
    private final GoogleServiceAccountTokens tokens;
    private final String zonePath;

    public GoogleCloudDnsRecordProvider(RestClient restClient, GoogleServiceAccountTokens tokens,
            String apiBase, String project, String zone) {
        this.restClient = restClient;
        this.tokens = tokens;
        this.zonePath = apiBase + "/projects/" + project + "/managedZones/" + zone;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void ensure(String fqdn, DnsRecordType type, List<String> values, int ttlSeconds) {
        String name = DnsNames.absolute(fqdn);
        List<String> rrdatas = wireValues(type, values);
        String what = type + " " + fqdn;
        Response existing = call(HttpMethod.GET, rrsetPath(name, type), null);
        if (existing.status() == 200) {
            JsonNode current = existing.json();
            List<String> currentData = new ArrayList<>();
            current.path("rrdatas").forEach(v -> currentData.add(v.asString()));
            if (sameData(type, currentData, rrdatas)
                    && current.path("ttl").asInt(-1) == ttlSeconds) {
                return; // already exactly this
            }
            require(call(HttpMethod.PATCH, rrsetPath(name, type),
                            rrset(name, type, rrdatas, ttlSeconds)), "ensure " + what, 200);
            log.info("cloud-dns replaced {} -> {}", what, rrdatas);
            return;
        }
        if (existing.status() != 404) {
            throw failure("read " + what, existing);
        }
        Response created = call(HttpMethod.POST, zonePath + "/rrsets",
                rrset(name, type, rrdatas, ttlSeconds));
        if (created.status() == 409) {
            // Lost a create race against ourselves (two applies for one name);
            // the set exists now, so make it hold what was asked.
            require(call(HttpMethod.PATCH, rrsetPath(name, type),
                            rrset(name, type, rrdatas, ttlSeconds)), "ensure " + what, 200);
        } else {
            require(created, "create " + what, 200, 201);
        }
        log.info("cloud-dns created {} -> {}", what, rrdatas);
    }

    @Override
    public void remove(String fqdn, DnsRecordType type) {
        String name = DnsNames.absolute(fqdn);
        Response deleted = call(HttpMethod.DELETE, rrsetPath(name, type), null);
        if (deleted.status() == 404) {
            return; // absent is the desired state
        }
        require(deleted, "remove " + type + " " + fqdn, 200, 204);
        log.info("cloud-dns removed {} {}", type, fqdn);
    }

    /**
     * The data as the zone API stores it. Only TXT differs from what the caller
     * gave: DNS carries it as a quoted character-string, so a value written raw
     * would come back quoted and never compare equal again.
     */
    private static List<String> wireValues(DnsRecordType type, List<String> values) {
        return type == DnsRecordType.TXT ? TxtValues.encodeAll(values) : List.copyOf(values);
    }

    /**
     * Whether the set already holds what is wanted. TXT is compared on the
     * decoded values rather than the presentation form, because a value the
     * zone holds as several character-strings, or escaped differently from the
     * way this client would write it, is the same TXT record and must not
     * provoke a rewrite on every reconcile.
     */
    private static boolean sameData(DnsRecordType type, List<String> current, List<String> wanted) {
        return type == DnsRecordType.TXT
                ? TxtValues.decodeAll(current).equals(TxtValues.decodeAll(wanted))
                : current.equals(wanted);
    }

    @Override
    public List<DnsRecord> listRecords(String rootDomain) {
        List<DnsRecord> records = new ArrayList<>();
        String pageToken = null;
        do {
            String path = zonePath + "/rrsets?maxResults=" + LIST_PAGE_SIZE
                    + (pageToken != null ? "&pageToken=" + pageToken : "");
            Response page = require(call(HttpMethod.GET, path, null), "list " + rootDomain, 200);
            for (JsonNode set : page.json().path("rrsets")) {
                List<String> values = new ArrayList<>();
                set.path("rrdatas").forEach(v -> values.add(v.asString()));
                records.add(new DnsRecord(set.path("name").asString(), set.path("type").asString(),
                        values, set.path("ttl").asInt(0)));
            }
            JsonNode next = page.json().path("nextPageToken");
            pageToken = next.isString() && !next.asString().isBlank() ? next.asString() : null;
        } while (pageToken != null);
        return records;
    }

    private String rrsetPath(String absoluteName, DnsRecordType type) {
        return zonePath + "/rrsets/" + absoluteName + "/" + type;
    }

    private static Map<String, Object> rrset(String absoluteName, DnsRecordType type,
            List<String> rrdatas, int ttlSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", absoluteName);
        body.put("type", type.name());
        body.put("ttl", ttlSeconds);
        body.put("rrdatas", rrdatas);
        return body;
    }

    private record Response(int status, String body) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
            } catch (RuntimeException e) {
                return JSON.createObjectNode();
            }
        }

        String errorMessage() {
            JsonNode message = json().path("error").path("message");
            return message.isString() ? message.asString() : body;
        }
    }

    private Response call(HttpMethod method, String uri, Object body) {
        try {
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
            }
            return request.exchange((req, response) -> new Response(
                    response.getStatusCode().value(), readBody(response.getBody())));
        } catch (ResourceAccessException e) {
            throw new DnsProviderException("Cloud DNS 연결 실패: " + e.getMessage(), e);
        }
    }

    private static Response require(Response response, String what, int... okStatuses) {
        for (int ok : okStatuses) {
            if (response.status() == ok) {
                return response;
            }
        }
        throw failure(what, response);
    }

    private static DnsProviderException failure(String what, Response response) {
        return new DnsProviderException("Cloud DNS " + what + " HTTP " + response.status()
                + ": " + response.errorMessage());
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
