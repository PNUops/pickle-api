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
 * currently v0.6.0) and the springdoc runtime spec.
 *
 * <p>The comparison is bidirectional over path+method sets: the contract must
 * equal {@link #IMPLEMENTED} ∪ {@link #PLANNED}, and the runtime must expose
 * exactly {@link #IMPLEMENTED} — nothing more, nothing less. Both sets are
 * named explicitly so a drift failure points at the exact endpoint instead of
 * a set diff of unknown origin.</p>
 *
 * <p>{@link #PLANNED} holds contract operations not yet implemented,
 * enabling parallel per-milestone development against a frozen contract. As each endpoint
 * lands, move its entry from PLANNED to IMPLEMENTED. <b>PLANNED must be empty
 * at each milestone end.</b></p>
 *
 * <p><b>Limitation:</b> only METHOD+path sets are compared. Parameters,
 * request/response schema shapes, and error codes are NOT checked here —
 * shape conformance is verified by manual contract review at each milestone
 * review gate.</p>
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

    /** The implemented surface ("METHOD path") — runtime must expose exactly this. */
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
            "POST /admin/orgs",
            "PATCH /admin/orgs/{orgId}",
            "PATCH /admin/users/{userId}",
            "GET /admin/nodes",
            "GET /admin/vms",
            // M4A HTTP publishing (contract v0.4.0).
            "POST /vms/{vmId}/publish",
            "PATCH /vms/{vmId}/publication",
            "DELETE /vms/{vmId}/publication",
            "GET /domains",
            "GET /domains/{domainId}",
            "DELETE /domains/{domainId}",
            "POST /domains/{domainId}/verify",
            "GET /admin/routes",
            "GET /admin/domains",
            "GET /admin/certificates",
            "POST /admin/routes/resync",
            // M5 api-A (contract v0.5.0).
            "GET /admin/settings",
            "PUT /admin/settings/{key}",
            "GET /notifications",
            "GET /notifications/unread-count",
            "POST /notifications/{notificationId}/read",
            "POST /notifications/read-all",
            "POST /admin/announcements",
            "GET /admin/announcements",
            "GET /admin/groups",
            "GET /me/activity",
            "GET /admin/audit",
            // M5 api-B (contract v0.5.0).
            "GET /admin/drift-findings",
            "POST /admin/drift-findings/{findingId}/resolve",
            "GET /admin/tasks",
            "POST /admin/tasks/{taskId}/retry",
            "GET /admin/ip-allocations",
            "GET /admin/summary",
            "GET /admin/system-summary",
            "PATCH /admin/vms/{vmId}/period",
            "GET /admin/notifications",
            "POST /admin/notifications/{notificationId}/resend",
            // M5.5 per-user SSH keys (contract v0.8.0).
            "GET /me/ssh-keys",
            "POST /me/ssh-keys",
            "POST /me/ssh-keys/generate",
            "DELETE /me/ssh-keys/{keyId}",
            "GET /me/ssh-keys/{keyId}/private-key",
            "GET /vms/{vmId}/settings",
            "PATCH /vms/{vmId}/settings",
            "POST /vms/{vmId}/password/regenerate",
            // M6 account self-service — Lane A (contract v0.9.0).
            "POST /auth/password-reset",
            "POST /auth/password-reset/confirm",
            "POST /me/withdraw",
            "PUT /me/password",
            // M6 admin user surface — Lane A (contract v0.9.0).
            "GET /admin/users",
            "GET /admin/users/{userId}",
            "POST /admin/users/{userId}/disable",
            "POST /admin/users/{userId}/enable");

    /**
     * Contract v0.9.0 (M6 account &amp; ops readiness) operations not implemented
     * yet. Contract = {@link #IMPLEMENTED} ∪ PLANNED; runtime = IMPLEMENTED.
     * Each entry moves to IMPLEMENTED as its endpoint lands, and PLANNED must be
     * empty again by the end of M6.
     *
     * <p>Lane ownership — Lane A (account lifecycle): password change/reset,
     * withdraw, admin users list/detail/disable/enable. Lane B: group delete.
     * W2-A (2FA + consent): mfa ops, mfa-reset, terms/consents. W2-B: meta
     * status. Keep entries alphabetized one-per-line to make lane merges
     * trivial.</p>
     */
    private static final Set<String> PLANNED = Set.of(
            "DELETE /groups/{groupId}",
            "GET /me/consents",
            "GET /meta/status",
            "GET /meta/terms",
            "GET /meta/terms/{docType}",
            "POST /admin/users/{userId}/mfa-reset",
            "POST /auth/mfa",
            "POST /me/consents",
            "POST /me/mfa/disable",
            "POST /me/mfa/recovery-codes",
            "POST /me/mfa/totp",
            "POST /me/mfa/totp/activate");

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

        // doesNotContainAnyElementsOf rejects an empty iterable with an
        // IllegalArgumentException, so guard the end-of-M3 state (PLANNED empty).
        if (!PLANNED.isEmpty()) {
            assertThat(IMPLEMENTED)
                    .as("IMPLEMENTED and PLANNED must be disjoint — an endpoint that "
                            + "landed must leave PLANNED")
                    .doesNotContainAnyElementsOf(PLANNED);
        }

        Set<String> contractSurface = new TreeSet<>(IMPLEMENTED);
        contractSurface.addAll(PLANNED);
        assertThat(endpointsOf(contract, ""))
                .as("contract path+method set vs IMPLEMENTED ∪ PLANNED")
                .isEqualTo(contractSurface);

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
