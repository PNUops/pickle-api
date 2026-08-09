package kr.ac.pusan.pickle.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 1:1 sudo-mode audit, the {@link RequireReauth} counterpart of
 * {@code PermissionMatrixTest}: the set of endpoints the running application
 * puts behind re-authentication must equal exactly the set declared here.
 *
 * <p>Nothing else pins this list. The annotation is a single line on a handler,
 * so both directions are silent failures without this test: adding one and
 * forgetting the contract/docs ships an undocumented 401 the console cannot
 * anticipate, and dropping one quietly removes a step-up gate from a
 * destructive or credential-revealing operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ReauthCoverageTest {

    private static final String API_PREFIX = "/api/v1";

    /**
     * The operator-approved sudo-mode surface: destructive VM operations, VM
     * credential reveal/rotation, every SSH-key write plus the private-key
     * download, and group membership changes.
     */
    private static final Set<String> DECLARED_REAUTH_ENDPOINTS = new TreeSet<>(Set.of(
            "POST /admin/relays/{relayId}/token",
            "DELETE /vms/{vmId}",
            "GET /vms/{vmId}/password",
            "POST /vms/{vmId}/password/regenerate",
            "PATCH /vms/{vmId}/settings",
            "POST /me/ssh-keys",
            "POST /me/ssh-keys/generate",
            "DELETE /me/ssh-keys/{keyId}",
            "GET /me/ssh-keys/{keyId}/private-key",
            "POST /groups/{groupId}/members",
            "PATCH /groups/{groupId}/members/{userId}",
            "DELETE /groups/{groupId}/members/{userId}",
            // Editing a VM's access list is what decides who reaches the VM at
            // all, so it steps up exactly as changing group membership does.
            // Reading the list does not.
            "POST /vms/{vmId}/access",
            "PATCH /vms/{vmId}/access/{grantId}",
            "DELETE /vms/{vmId}/access/{grantId}"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void reauthGatedEndpointsMatchTheDeclaredSetExactly() {
        Set<String> runtime = runtimeReauthEndpoints();

        Set<String> undeclared = new TreeSet<>(runtime);
        undeclared.removeAll(DECLARED_REAUTH_ENDPOINTS);
        Set<String> missing = new TreeSet<>(DECLARED_REAUTH_ENDPOINTS);
        missing.removeAll(runtime);

        assertThat(undeclared)
                .as("endpoints newly gated by @RequireReauth: if that is intended, add them to "
                        + "DECLARED_REAUTH_ENDPOINTS and update the contract and the sudo-mode "
                        + "documentation in the same unit of work")
                .isEmpty();
        assertThat(missing)
                .as("endpoints that lost their @RequireReauth gate: if the step-up requirement was "
                        + "deliberately dropped, remove them from DECLARED_REAUTH_ENDPOINTS and "
                        + "update the contract and the sudo-mode documentation in the same unit of "
                        + "work")
                .isEmpty();
        assertThat(runtime)
                .as("sudo-mode surface size (kept explicit so a swap of one endpoint for another "
                        + "still reads as a deliberate edit)")
                .hasSize(DECLARED_REAUTH_ENDPOINTS.size());
    }

    /**
     * "METHOD /path" (no {@code /api/v1}) for every handler the sudo-mode
     * interceptor would gate — same lookup as {@code ReauthInterceptor}:
     * merged annotation on the handler method, else on its bean type.
     */
    private Set<String> runtimeReauthEndpoints() {
        Set<String> gated = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("kr.ac.pusan.pickle")
                    || !requiresReauth(handler)) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                // The interceptor is registered for /api/v1/** only — an
                // annotated handler outside that prefix would be silently
                // UN-gated at runtime, so it must fail here, not be skipped.
                assertThat(pattern)
                        .as("@RequireReauth handler %s is mapped outside %s — the reauth "
                                + "interceptor would never run for it", handler, API_PREFIX)
                        .startsWith(API_PREFIX);
                String path = pattern.substring(API_PREFIX.length());
                info.getMethodsCondition().getMethods()
                        .forEach(method -> gated.add(method.name() + " " + path));
            }
        });
        return gated;
    }

    private static boolean requiresReauth(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RequireReauth.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RequireReauth.class);
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition().getPatterns();
    }
}
