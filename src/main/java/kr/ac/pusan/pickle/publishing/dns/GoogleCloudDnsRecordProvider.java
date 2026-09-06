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
 * <p>Per-record-set endpoints ({@code GET/POST/PATCH/DELETE rrsets/{name}/A})
 * are used instead of the older {@code changes} batch because they make the
 * idempotence the contract promises trivial: an ensure reads the set and
 * patches only when it differs, a remove treats 404 as done.</p>
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
    public void ensureA(String fqdn, String ipv4, int ttlSeconds) {
        String name = DnsNames.absolute(fqdn);
        Response existing = call(HttpMethod.GET, rrsetPath(name), null);
        if (existing.status() == 200) {
            JsonNode current = existing.json();
            List<String> rrdatas = new ArrayList<>();
            current.path("rrdatas").forEach(v -> rrdatas.add(v.asString()));
            if (rrdatas.equals(List.of(ipv4)) && current.path("ttl").asInt(-1) == ttlSeconds) {
                return; // already exactly this
            }
            require(call(HttpMethod.PATCH, rrsetPath(name), rrset(name, ipv4, ttlSeconds)),
                    "ensure " + fqdn, 200);
            log.info("cloud-dns replaced A {} -> {}", fqdn, ipv4);
            return;
        }
        if (existing.status() != 404) {
            throw failure("read " + fqdn, existing);
        }
        Response created = call(HttpMethod.POST, zonePath + "/rrsets", rrset(name, ipv4, ttlSeconds));
        if (created.status() == 409) {
            // Lost a create race against ourselves (two applies for one name);
            // the set exists now, so make it hold what was asked.
            require(call(HttpMethod.PATCH, rrsetPath(name), rrset(name, ipv4, ttlSeconds)),
                    "ensure " + fqdn, 200);
        } else {
            require(created, "create " + fqdn, 200, 201);
        }
        log.info("cloud-dns created A {} -> {}", fqdn, ipv4);
    }

    @Override
    public void removeA(String fqdn) {
        String name = DnsNames.absolute(fqdn);
        Response deleted = call(HttpMethod.DELETE, rrsetPath(name), null);
        if (deleted.status() == 404) {
            return; // absent is the desired state
        }
        require(deleted, "remove " + fqdn, 200, 204);
        log.info("cloud-dns removed A {}", fqdn);
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

    private String rrsetPath(String absoluteName) {
        return zonePath + "/rrsets/" + absoluteName + "/A";
    }

    private static Map<String, Object> rrset(String absoluteName, String ipv4, int ttlSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", absoluteName);
        body.put("type", "A");
        body.put("ttl", ttlSeconds);
        body.put("rrdatas", List.of(ipv4));
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
