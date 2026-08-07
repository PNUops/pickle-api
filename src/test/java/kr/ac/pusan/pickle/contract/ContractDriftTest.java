package kr.ac.pusan.pickle.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the API contract surface from three directions.
 *
 * <p><b>1. Published spec snapshot</b> — {@code contract/openapi.yaml} (the
 * committed, generated as-built spec) must equal the springdoc runtime spec.
 * Regenerate it after any endpoint change with
 * {@code mvn test -Dtest=ContractDriftTest -Dcontract.update=true}.</p>
 *
 * <p><b>2. Implemented set</b> — the runtime must expose exactly
 * {@link #IMPLEMENTED}, nothing more, nothing less. Both directions are named
 * explicitly so a drift failure points at the exact endpoint.</p>
 *
 * <p><b>3. Design contract (optional)</b> — when the environment variable
 * {@code PICKLE_CONTRACT_MASTER} points at the hand-written design contract,
 * its path+method set must equal {@link #IMPLEMENTED} ∪ {@link #PLANNED}.
 * {@link #PLANNED} holds contract operations not yet implemented, enabling
 * parallel development against a frozen design contract; it must be empty
 * once the matching endpoints ship.</p>
 *
 * <p><b>Limitation:</b> checks 2 and 3 compare METHOD+path sets only.
 * Parameters, schema shapes and error codes are covered by check 1 (the
 * published snapshot is byte-stable) and by contract review.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ContractDriftTest {

    /** Published generated spec, relative to the module root (surefire cwd). */
    private static final Path PUBLISHED = Path.of("contract", "openapi.yaml");

    /** Contract server prefix stripped from runtime paths before comparison. */
    private static final String SERVER_PREFIX = "/api/v1";

    /** The implemented surface ("METHOD path") — runtime must expose exactly this. */
    private static final Set<String> IMPLEMENTED = Set.of(
            "POST /auth/signup",
            "POST /auth/verify-email",
            "POST /auth/resend-verification",
            "POST /auth/login",
            "POST /auth/refresh",
            "POST /auth/logout",
            "POST /auth/reverify",
            "GET /me",
            "GET /orgs",
            "GET /templates",
            "GET /meta/request-options",
            "GET /groups",
            "POST /groups",
            "GET /groups/{groupId}",
            "PATCH /groups/{groupId}",
            "DELETE /groups/{groupId}",
            "POST /groups/{groupId}/members",
            "PATCH /groups/{groupId}/members/{userId}",
            "DELETE /groups/{groupId}/members/{userId}",
            "POST /vm-requests",
            "GET /vm-requests",
            "GET /vm-requests/{requestId}",
            "POST /vm-requests/{requestId}/cancel",
            "GET /vms",
            "GET /vms/{vmId}",
            "DELETE /vms/{vmId}",
            "POST /admin/vms/{vmId}/schedule-delete",
            "POST /admin/vms/{vmId}/cancel-scheduled-delete",
            "POST /admin/vms/{vmId}/force-delete",
            "POST /vms/{vmId}/start",
            "POST /vms/{vmId}/shutdown",
            "POST /vms/{vmId}/reboot",
            "POST /vms/{vmId}/force-stop",
            "GET /vms/{vmId}/password",
            "GET /vms/{vmId}/events",
            "GET /admin/vm-requests",
            "GET /admin/vm-requests/{requestId}",
            "GET /admin/vm-requests/{requestId}/context",
            "POST /admin/vm-requests/{requestId}/approve",
            "POST /admin/vm-requests/{requestId}/reject",
            "GET /admin/orgs",
            "POST /admin/orgs",
            "PATCH /admin/orgs/{orgId}",
            "PATCH /admin/users/{userId}",
            "GET /admin/nodes",
            "PATCH /admin/nodes/{nodeId}",
            "GET /admin/templates",
            "PATCH /admin/templates/{templateId}",
            "GET /admin/vms",
            // HTTP publishing (multi-domain since contract v0.29.0).
            "POST /vms/{vmId}/domains",
            "GET /domains",
            "GET /domains/{domainId}",
            "PATCH /domains/{domainId}",
            "DELETE /domains/{domainId}",
            "POST /domains/{domainId}/verify",
            "GET /admin/routes",
            "GET /admin/domains",
            "GET /admin/certificates",
            "POST /admin/routes/resync",
            "POST /admin/routes/{routeId}/apply",
            "POST /admin/domains/{domainId}/force-release",
            "POST /admin/domains/{domainId}/verify",
            // Notifications, announcements, audit views (contract v0.5.0).
            "GET /admin/settings",
            "PUT /admin/settings/{key}",
            "GET /notifications",
            "GET /notifications/unread-count",
            "POST /notifications/{notificationId}/read",
            "POST /notifications/read-all",
            "POST /admin/announcements",
            "GET /admin/announcements",
            "GET /admin/groups",
            "GET /admin/groups/{groupId}",
            "GET /me/activity",
            "GET /admin/audit",
            // Ops dashboards, drift, tasks, expiry (contract v0.5.0).
            "GET /admin/drift-findings",
            "POST /admin/drift-findings/{findingId}/resolve",
            "GET /admin/tasks",
            "POST /admin/tasks/{taskId}/retry",
            "GET /admin/ip-allocations",
            "GET /admin/summary",
            "GET /admin/system-summary",
            "PATCH /admin/vms/{vmId}/period",
            "PATCH /admin/vms/{vmId}/gateway-block",
            "GET /admin/vms/{vmId}",
            "GET /admin/vms/{vmId}/events",
            "POST /admin/vms/{vmId}/start",
            "POST /admin/vms/{vmId}/shutdown",
            "POST /admin/vms/{vmId}/reboot",
            "POST /admin/vms/{vmId}/force-stop",
            "GET /admin/notifications",
            "POST /admin/notifications/{notificationId}/resend",
            // Per-user SSH keys and VM settings (contract v0.8.0).
            "GET /me/ssh-keys",
            "POST /me/ssh-keys",
            "POST /me/ssh-keys/generate",
            "DELETE /me/ssh-keys/{keyId}",
            "GET /me/ssh-keys/{keyId}/private-key",
            "GET /vms/{vmId}/settings",
            "PATCH /vms/{vmId}/settings",
            "POST /vms/{vmId}/password/regenerate",
            // Account self-service (contract v0.9.0).
            "POST /auth/password-reset",
            "POST /auth/password-reset/confirm",
            "POST /me/withdraw",
            "PUT /me/password",
            // Admin user surface (contract v0.9.0).
            "GET /admin/users",
            "GET /admin/users/{userId}",
            "POST /admin/users/{userId}/disable",
            "POST /admin/users/{userId}/enable",
            // 2FA enrollment (contract v0.9.0).
            "POST /me/mfa/totp",
            "POST /me/mfa/totp/activate",
            "POST /me/mfa/disable",
            "POST /me/mfa/recovery-codes",
            "POST /auth/mfa",
            "POST /admin/users/{userId}/mfa-reset",
            // Terms and consent (contract v0.9.0).
            "GET /meta/terms",
            "GET /meta/terms/{docType}",
            "GET /me/consents",
            "POST /me/consents",
            // Maintenance and contact (contract v0.9.0).
            "GET /meta/status",
            // Web terminal (contract v0.10.0).
            "POST /vms/{vmId}/terminal-sessions",
            "GET /admin/terminal-sessions",
            "POST /admin/terminal-sessions/{sessionId}/terminate",
            // Spec presets, the second request axis (contract v0.23.0).
            "GET /vm-flavors",
            "GET /admin/vm-flavors",
            "POST /admin/vm-flavors",
            "PATCH /admin/vm-flavors/{flavorId}",
            // Relay port forwarding + 교내 IP (contract v0.27.0).
            "GET /vms/{vmId}/port-forwardings",
            "POST /vms/{vmId}/port-forwardings",
            "DELETE /vms/{vmId}/port-forwardings/{portForwardingId}",
            "GET /admin/relays",
            "POST /admin/relays/{relayId}/token",
            "GET /admin/port-mappings",
            "POST /admin/port-mappings/{mappingId}/suspend",
            "POST /admin/port-mappings/{mappingId}/unsuspend",
            "DELETE /admin/port-mappings/{mappingId}",
            "PATCH /admin/port-mappings/{mappingId}/guards",
            "GET /vms/{vmId}/campus-ip-requests",
            "POST /vms/{vmId}/campus-ip-requests",
            "DELETE /vms/{vmId}/campus-ip-requests/{requestId}",
            "GET /admin/campus-ip-requests",
            "POST /admin/campus-ip-requests/{requestId}/status");

    /**
     * Design-contract operations not implemented yet. Design contract =
     * {@link #IMPLEMENTED} ∪ PLANNED; runtime = IMPLEMENTED. Each entry moves
     * to IMPLEMENTED as its endpoint lands; PLANNED must be empty again once
     * every entry ships. Keep entries alphabetized one-per-line if a
     * future rev repopulates this set.
     */
    private static final Set<String> PLANNED = Set.of();

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishedSpecMatchesRuntime() throws Exception {
        JsonNode runtime = fetchRuntimeSpec();
        String canonical = toCanonicalYaml(toPublishedSpec(runtime));

        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(PUBLISHED.getParent());
            Files.writeString(PUBLISHED, canonical);
        }

        assertThat(PUBLISHED)
                .as("published spec contract/openapi.yaml — regenerate with -Dcontract.update=true")
                .exists();
        assertThat(Files.readString(PUBLISHED))
                .as("contract/openapi.yaml is stale — regenerate with -Dcontract.update=true")
                .isEqualTo(canonical);
    }

    @Test
    void runtimeExposesExactlyTheImplementedSet() throws Exception {
        assertThat(endpointsOf(fetchRuntimeSpec(), SERVER_PREFIX))
                .as("springdoc runtime spec path+method set vs the implemented set")
                .isEqualTo(new TreeSet<>(IMPLEMENTED));
    }

    @Test
    void designContractSurfaceMatchesImplementedPlusPlanned() throws Exception {
        String master = System.getenv("PICKLE_CONTRACT_MASTER");
        Assumptions.assumeTrue(master != null && !master.isBlank(),
                "PICKLE_CONTRACT_MASTER not set — design-contract comparison skipped");

        Path masterPath = Path.of(master);
        assertThat(masterPath).as("design contract at $PICKLE_CONTRACT_MASTER").exists();
        JsonNode contract = new YAMLMapper().readTree(Files.readString(masterPath));

        // doesNotContainAnyElementsOf rejects an empty iterable with an
        // IllegalArgumentException, so guard the all-shipped state (PLANNED empty).
        if (!PLANNED.isEmpty()) {
            assertThat(IMPLEMENTED)
                    .as("IMPLEMENTED and PLANNED must be disjoint — an endpoint that "
                            + "landed must leave PLANNED")
                    .doesNotContainAnyElementsOf(PLANNED);
        }

        Set<String> contractSurface = new TreeSet<>(IMPLEMENTED);
        contractSurface.addAll(PLANNED);
        assertThat(endpointsOf(contract, ""))
                .as("design contract path+method set vs IMPLEMENTED ∪ PLANNED")
                .isEqualTo(contractSurface);
    }

    /**
     * Published-spec convention: path keys are server-relative and the prefix
     * lives in {@code servers[0].url} — matching how typed clients (the console
     * openapi-fetch client) key operations on unprefixed literals and prepend
     * the base URL themselves.
     */
    private static JsonNode toPublishedSpec(JsonNode runtime) {
        com.fasterxml.jackson.databind.node.ObjectNode spec = runtime.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode strippedPaths =
                spec.objectNode();
        JsonNode paths = spec.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = entry.getKey();
            if (path.startsWith(SERVER_PREFIX)) {
                path = path.substring(SERVER_PREFIX.length());
            }
            strippedPaths.set(path, entry.getValue());
        }
        spec.set("paths", strippedPaths);
        com.fasterxml.jackson.databind.node.ArrayNode servers = spec.arrayNode();
        servers.add(spec.objectNode().put("url", SERVER_PREFIX));
        spec.set("servers", servers);
        return spec;
    }

    private JsonNode fetchRuntimeSpec() throws Exception {
        String runtimeJson = mockMvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(runtimeJson);
    }

    /**
     * Serializes the spec as YAML with alphabetically ordered keys so the
     * published file is byte-stable across regenerations (array order — e.g.
     * parameter lists — is preserved as emitted by springdoc).
     */
    private static String toCanonicalYaml(JsonNode spec) throws Exception {
        YAMLMapper yaml = new YAMLMapper();
        yaml.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        Object tree = new ObjectMapper().convertValue(spec, Object.class);
        return yaml.writeValueAsString(tree);
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
