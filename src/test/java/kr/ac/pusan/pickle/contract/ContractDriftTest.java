package kr.ac.pusan.pickle.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards against drift between the frozen contract (docs/api/openapi.yaml,
 * v0.2.0) and the springdoc runtime spec.
 *
 * <p>WP-B1 scope: only the endpoints implemented so far (auth + /me) are
 * compared — extend {@link #IMPLEMENTED} as work packages land. WP-B3 will
 * assert full path+method set equality. Within the covered prefixes the test
 * fails when an implemented endpoint is missing from either side, or when
 * either side has an endpoint that is not implemented.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ContractDriftTest {

    /** Contract file, relative to the api module (surefire cwd = module root). */
    private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi.yaml");

    /** Contract server prefix stripped from runtime paths before comparison. */
    private static final String SERVER_PREFIX = "/api/v1";

    /** Endpoints implemented so far ("METHOD path"). Extend per work package. */
    private static final Set<String> IMPLEMENTED = Set.of(
            "POST /auth/signup",
            "POST /auth/verify-email",
            "POST /auth/resend-verification",
            "POST /auth/login",
            "POST /auth/refresh",
            "POST /auth/logout",
            "GET /me");

    /**
     * Path roots covered by the WP-B1 subset comparison: a path is covered
     * when it equals a root or lives beneath it ("/me" covers "/me" and
     * "/me/…" but not "/meta/…").
     */
    private static final Set<String> COVERED_ROOTS = Set.of("/auth", "/me");

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void implementedEndpointsMatchContractAndRuntimeSpec() throws Exception {
        assertThat(CONTRACT).as("contract file docs/api/openapi.yaml").exists();
        JsonNode contract = new YAMLMapper().readTree(Files.readString(CONTRACT));

        String runtimeJson = mockMvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode runtime = new com.fasterxml.jackson.databind.ObjectMapper().readTree(runtimeJson);

        Set<String> contractSubset = coveredSubset(endpointsOf(contract, ""));
        Set<String> runtimeSubset = coveredSubset(endpointsOf(runtime, SERVER_PREFIX));

        assertThat(contractSubset)
                .as("contract (auth + /me) vs endpoints implemented in WP-B1")
                .isEqualTo(new TreeSet<>(IMPLEMENTED));
        assertThat(runtimeSubset)
                .as("springdoc runtime spec (auth + /me) vs endpoints implemented in WP-B1")
                .isEqualTo(new TreeSet<>(IMPLEMENTED));
    }

    /** Extracts "METHOD path" pairs, normalizing away {@code stripPrefix}. */
    private static Set<String> endpointsOf(JsonNode spec, String stripPrefix) {
        Set<String> endpoints = new TreeSet<>();
        JsonNode paths = spec.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = entry.getKey();
            if (!stripPrefix.isEmpty() && path.startsWith(stripPrefix)) {
                path = path.substring(stripPrefix.length());
            }
            for (Iterator<String> methods = entry.getValue().fieldNames(); methods.hasNext(); ) {
                String method = methods.next();
                if (HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                    endpoints.add(method.toUpperCase(Locale.ROOT) + " " + path);
                }
            }
        }
        return endpoints;
    }

    private static Set<String> coveredSubset(Set<String> endpoints) {
        Set<String> subset = new TreeSet<>();
        for (String endpoint : endpoints) {
            String path = endpoint.split(" ", 2)[1];
            boolean covered = COVERED_ROOTS.stream()
                    .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
            if (covered) {
                subset.add(endpoint);
            }
        }
        return subset;
    }
}
