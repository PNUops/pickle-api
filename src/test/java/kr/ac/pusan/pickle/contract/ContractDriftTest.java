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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * <p><b>4. Design-contract names (optional)</b> — the design contract states
 * that it reuses the generated names verbatim, so for every operation the two
 * documents share, their {@code operationId} must be equal, and every schema
 * the design contract names must exist under the generated name. Check 3 alone
 * passes on a name mismatch because it compares path+method only; 20 operation
 * ids had drifted that way unnoticed before the 2026-08-08 alignment, which is
 * why this axis is a gate rather than a manual comparison step.</p>
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
            "POST /auth/oauth/google/start",
            "POST /auth/oauth/google/callback",
            "POST /auth/oauth/google/complete",
            "GET /me",
            "PUT /me/profile",
            "DELETE /me/identities/{provider}",
            "GET /orgs",
            "GET /os-images",
            "GET /meta/request-options",
            "GET /meta/profile-options",
            "GET /workspaces",
            "POST /workspaces",
            "GET /workspaces/{workspaceId}",
            "PATCH /workspaces/{workspaceId}",
            "DELETE /workspaces/{workspaceId}",
            "POST /workspaces/{workspaceId}/members",
            "PATCH /workspaces/{workspaceId}/members/{userId}",
            "DELETE /workspaces/{workspaceId}/members/{userId}",
            "GET /resources",
            "POST /requests",
            "GET /requests",
            "GET /requests/{requestId}",
            "POST /requests/{requestId}/cancel",
            "GET /vms",
            "GET /vms/{vmId}",
            "DELETE /vms/{vmId}",
            "GET /vms/{vmId}/access",
            "POST /vms/{vmId}/access",
            "PATCH /vms/{vmId}/access/{grantId}",
            "DELETE /vms/{vmId}/access/{grantId}",
            "POST /admin/vms/{vmId}/schedule-delete",
            "POST /admin/vms/{vmId}/cancel-scheduled-delete",
            "POST /admin/vms/{vmId}/force-delete",
            "POST /vms/{vmId}/start",
            "POST /vms/{vmId}/shutdown",
            "POST /vms/{vmId}/reboot",
            "POST /vms/{vmId}/force-stop",
            "GET /vms/{vmId}/password",
            "GET /vms/{vmId}/events",
            "GET /admin/requests",
            "GET /admin/requests/{requestId}",
            "GET /admin/requests/{requestId}/context",
            "POST /admin/requests/{requestId}/approve",
            "POST /admin/requests/{requestId}/reject",
            "GET /admin/llm/keys",
            "GET /admin/llm/keys/{keyId}",
            "PUT /admin/llm/keys/{keyId}/limits",
            "POST /admin/llm/keys/{keyId}/suspend",
            "POST /admin/llm/keys/{keyId}/resume",
            "GET /admin/orgs",
            "POST /admin/orgs",
            "PATCH /admin/orgs/{orgId}",
            "PATCH /admin/users/{userId}",
            "PUT /admin/users/{userId}/org-roles/{orgId}",
            "DELETE /admin/users/{userId}/org-roles/{orgId}",
            "GET /admin/nodes",
            "PATCH /admin/nodes/{nodeId}",
            "GET /admin/os-images",
            "PATCH /admin/os-images/{imageId}",
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
            "GET /admin/workspaces",
            "GET /admin/workspaces/{workspaceId}",
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
            // Per-VM SSH keys (contract v0.42.0) and VM settings.
            "GET /vms/{vmId}/ssh-key",
            "POST /vms/{vmId}/ssh-key",
            "POST /vms/{vmId}/ssh-key/reissue",
            "GET /vms/{vmId}/ssh-key/private-key",
            "DELETE /vms/{vmId}/ssh-key",
            "GET /vms/{vmId}/settings",
            "PATCH /vms/{vmId}/settings",
            "POST /vms/{vmId}/password/regenerate",
            // Account self-service (contract v0.9.0).
            "POST /auth/password-reset",
            "POST /auth/password-reset/confirm",
            "POST /me/withdraw",
            "PUT /me/password",
            // First-time password set for a Google account (contract v0.46.0).
            "POST /me/password",
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
            "PATCH /admin/users/{userId}/profile",
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
            "POST /admin/campus-ip-requests/{requestId}/status",
            // Usage monitoring, read live from the hypervisor (contract v0.35.0).
            "GET /vms/{vmId}/metrics",
            "GET /admin/nodes/{nodeId}/metrics",
            "GET /admin/capacity-trend",
            // LLM API keys: the read surface and the access list.
            "GET /llm-keys",
            "GET /llm-keys/{keyId}",
            "GET /llm-keys/{keyId}/usage",
            "PATCH /llm-keys/{keyId}",
            "POST /llm-keys/{keyId}/token",
            "POST /llm-keys/{keyId}/revoke",
            "GET /llm-keys/{keyId}/access",
            "POST /llm-keys/{keyId}/access",
            "PATCH /llm-keys/{keyId}/access/{grantId}",
            "DELETE /llm-keys/{keyId}/access/{grantId}",
            // 공지사항: the public board and its management surface.
            "GET /notices",
            "GET /notices/{noticeId}",
            "GET /notices/{noticeId}/images/{imageId}",
            "GET /admin/notices",
            "POST /admin/notices",
            "PATCH /admin/notices/{noticeId}",
            "DELETE /admin/notices/{noticeId}",
            "POST /admin/notices/{noticeId}/images",
            "DELETE /admin/notices/{noticeId}/images/{imageId}");

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
        JsonNode contract = designContractOrSkip();

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

    @Test
    void designContractNamesMatchGeneratedNames() throws Exception {
        JsonNode contract = designContractOrSkip();
        JsonNode runtime = fetchRuntimeSpec();

        Map<String, String> generatedIds = operationIdsOf(runtime, SERVER_PREFIX);
        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, String> operation : operationIdsOf(contract, "").entrySet()) {
            String generated = generatedIds.get(operation.getKey());
            if (generated != null && !generated.equals(operation.getValue())) {
                drifted.add(operation.getKey() + " — design contract " + operation.getValue()
                        + ", generated " + generated);
            }
        }
        assertThat(drifted)
                .as("operationId drift, listed per operation the two specs share")
                .isEmpty();

        // Containment runs one way: the generated spec names every DTO the
        // runtime exposes, while the design contract names only the subset it
        // documents. An unimplemented design operation may carry schemas the
        // runtime cannot know yet, so the axis holds only while PLANNED is empty.
        if (PLANNED.isEmpty()) {
            Set<String> unknownSchemas = schemaNamesOf(contract);
            unknownSchemas.removeAll(schemaNamesOf(runtime));
            assertThat(unknownSchemas)
                    .as("design contract schema names absent from the generated spec")
                    .isEmpty();
        }
    }

    /** Reads the design contract, or skips the test when it was not pointed at. */
    private static JsonNode designContractOrSkip() throws Exception {
        String master = System.getenv("PICKLE_CONTRACT_MASTER");
        Assumptions.assumeTrue(master != null && !master.isBlank(),
                "PICKLE_CONTRACT_MASTER not set — design-contract comparison skipped");

        Path masterPath = Path.of(master);
        assertThat(masterPath).as("design contract at $PICKLE_CONTRACT_MASTER").exists();
        return new YAMLMapper().readTree(Files.readString(masterPath));
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

    /**
     * Maps each "METHOD path" to its declared {@code operationId}, normalizing
     * away {@code stripPrefix}. An operation without one maps to a placeholder
     * so a missing id reads as drift instead of silently matching.
     */
    private static Map<String, String> operationIdsOf(JsonNode spec, String stripPrefix) {
        Map<String, String> operationIds = new TreeMap<>();
        JsonNode paths = spec.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = entry.getKey();
            if (!stripPrefix.isEmpty() && path.startsWith(stripPrefix)) {
                path = path.substring(stripPrefix.length());
            }
            for (Iterator<Map.Entry<String, JsonNode>> ops = entry.getValue().properties().iterator();
                    ops.hasNext(); ) {
                Map.Entry<String, JsonNode> operation = ops.next();
                String method = operation.getKey();
                if (!HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                JsonNode operationId = operation.getValue().path("operationId");
                operationIds.put(method.toUpperCase(Locale.ROOT) + " " + path,
                        operationId.isTextual() ? operationId.asText() : "(no operationId)");
            }
        }
        return operationIds;
    }

    /** Names declared under {@code components.schemas}. */
    private static Set<String> schemaNamesOf(JsonNode spec) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = spec.path("components").path("schemas").fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
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
