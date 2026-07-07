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
 * v0.2.3) and the springdoc runtime spec.
 *
 * <p>Since WP-B3 the whole M2 surface is implemented, so the comparison is
 * bidirectional over the full path+method sets: every contract endpoint must
 * exist at runtime and the runtime must expose nothing beyond the contract.
 * {@link #IMPLEMENTED} names the expected set explicitly so a drift failure
 * points at the exact endpoint instead of a set diff of unknown origin.</p>
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

    /** The full contract v0.2.3 surface ("METHOD path"). */
    private static final Set<String> IMPLEMENTED = Set.of(
            "POST /auth/signup",
            "POST /auth/verify-email",
            "POST /auth/resend-verification",
            "POST /auth/login",
            "POST /auth/refresh",
            "POST /auth/logout",
            "GET /me",
            "GET /orgs",
            "GET /templates",
            "GET /meta/request-options",
            "GET /groups",
            "POST /groups",
            "GET /groups/{groupId}",
            "PATCH /groups/{groupId}",
            "POST /groups/{groupId}/members",
            "PATCH /groups/{groupId}/members/{userId}",
            "DELETE /groups/{groupId}/members/{userId}",
            "POST /vm-requests",
            "GET /vm-requests",
            "GET /vm-requests/{requestId}",
            "POST /vm-requests/{requestId}/cancel",
            "GET /vms",
            "GET /vms/{vmId}",
            "GET /admin/vm-requests",
            "GET /admin/vm-requests/{requestId}",
            "GET /admin/vm-requests/{requestId}/context",
            "POST /admin/vm-requests/{requestId}/approve",
            "POST /admin/vm-requests/{requestId}/reject",
            "POST /admin/orgs",
            "PATCH /admin/orgs/{orgId}",
            "PATCH /admin/users/{userId}");

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullContractSurfaceMatchesRuntimeSpecBidirectionally() throws Exception {
        assertThat(CONTRACT).as("contract file docs/api/openapi.yaml").exists();
        JsonNode contract = new YAMLMapper().readTree(Files.readString(CONTRACT));

        String runtimeJson = mockMvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode runtime = new com.fasterxml.jackson.databind.ObjectMapper().readTree(runtimeJson);

        assertThat(endpointsOf(contract, ""))
                .as("contract v0.2.3 path+method set vs the implemented set")
                .isEqualTo(new TreeSet<>(IMPLEMENTED));
        assertThat(endpointsOf(runtime, SERVER_PREFIX))
                .as("springdoc runtime spec path+method set vs the implemented set")
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
}
