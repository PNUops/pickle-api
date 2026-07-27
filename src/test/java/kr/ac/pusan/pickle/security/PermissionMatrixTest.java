package kr.ac.pusan.pickle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 1:1 authorization audit (closing the long-deferred audit item).
 * Transcribes {@code permission-matrix.yaml} — the operator-
 * approved policy — and asserts the live API enforces it exactly:
 *
 * <ul>
 *   <li>the set of {@code /api/v1} operations the app maps == the set in the
 *       YAML (no undocumented endpoint, no stale row);</li>
 *   <li>for every role-gated op the effective {@code @PreAuthorize} role set
 *       (method annotation overriding the class one, per Spring method security)
 *       equals exactly the roles whose cell is {@code allow}/{@code allow_org_scoped};</li>
 *   <li>self / catalog / group-scoped / {@code public} rows carry no
 *       {@code @PreAuthorize} — authorization is service-layer or none;</li>
 *   <li>every non-public op rejects an unauthenticated caller with 401, and
 *       every public op does not (the permitAll boundary).</li>
 * </ul>
 *
 * Org-scoping and group-role cells ({@code allow_org_scoped} / {@code
 * allow_group_scoped}) are service-layer and cannot be seen from annotations;
 * they are exercised by MockMvc tests ({@code ManagerRoleScopingTest} for the
 * new manager tiers, plus the per-surface admin tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PermissionMatrixTest {

    private static final List<String> ROLES =
            List.of("USER", "ORG_MANAGER", "ORG_ADMIN", "SYS_MANAGER", "SYS_ADMIN");
    private static final String API_PREFIX = "/api/v1";
    private static final Pattern ROLE_TOKEN = Pattern.compile("'([A-Z_]+)'");
    private static final Pattern PATH_VAR = Pattern.compile("\\{[^}]+}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private MockMvc mockMvc;

    /** operationId + per-role token map, keyed by "METHOD path" (no /api/v1). */
    private record Op(String id, String method, String path, Map<String, String> roles) {
        String key() {
            return method + " " + path;
        }
    }

    private Map<String, Op> loadMatrix() throws Exception {
        JsonNode doc = new YAMLMapper().readTree(
                getClass().getResource("/permission-matrix.yaml"));
        Map<String, Op> ops = new TreeMap<>();
        for (JsonNode node : doc.get("operations")) {
            String method = node.get("method").asText();
            String path = node.get("path").asText();
            Map<String, String> roles = new LinkedHashMap<>();
            JsonNode roleNode = node.get("roles");
            for (String role : ROLES) {
                assertThat(roleNode.has(role))
                        .as("permission-matrix.yaml op %s missing role %s", node.get("id"), role)
                        .isTrue();
                roles.put(role, roleNode.get(role).asText());
            }
            Op op = new Op(node.get("id").asText(), method, path, roles);
            assertThat(ops.put(op.key(), op)).as("duplicate op %s", op.key()).isNull();
        }
        return ops;
    }

    /** All /api/v1 handler mappings of this application, keyed by "METHOD path". */
    private Map<String, HandlerMethod> runtimeOps() {
        Map<String, HandlerMethod> ops = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("kr.ac.pusan.pickle")) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith(API_PREFIX)) {
                    return; // /internal/** and non-versioned paths are out of the contract
                }
                String path = pattern.substring(API_PREFIX.length());
                info.getMethodsCondition().getMethods().forEach(m ->
                        ops.put(m.name() + " " + path, handler));
            }
        });
        return ops;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition().getPatterns();
    }

    /** Effective @PreAuthorize role set (method overrides class), or null if none. */
    private static Set<String> gateRoles(HandlerMethod handler) {
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(),
                PreAuthorize.class);
        if (pre == null) {
            pre = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(),
                    PreAuthorize.class);
        }
        if (pre == null) {
            return null;
        }
        Set<String> roles = new TreeSet<>();
        Matcher matcher = ROLE_TOKEN.matcher(pre.value());
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    @Test
    void runtimeEndpointSetMatchesTheMatrixExactly() throws Exception {
        Map<String, Op> matrix = loadMatrix();
        Set<String> runtime = runtimeOps().keySet();

        assertThat(matrix).as("permission-matrix.yaml op count (contract v0.19.0)").hasSize(117);

        Set<String> missingFromMatrix = new TreeSet<>(runtime);
        missingFromMatrix.removeAll(matrix.keySet());
        Set<String> missingFromRuntime = new TreeSet<>(matrix.keySet());
        missingFromRuntime.removeAll(runtime);
        assertThat(missingFromMatrix)
                .as("endpoints mapped by the app but absent from permission-matrix.yaml")
                .isEmpty();
        assertThat(missingFromRuntime)
                .as("permission-matrix.yaml rows with no matching app endpoint")
                .isEmpty();
    }

    @Test
    void everyGateMatchesTheMatrix() throws Exception {
        Map<String, Op> matrix = loadMatrix();
        Map<String, HandlerMethod> runtime = runtimeOps();

        for (Op op : matrix.values()) {
            HandlerMethod handler = runtime.get(op.key());
            assertThat(handler).as("no handler for %s (%s)", op.key(), op.id()).isNotNull();
            Set<String> actual = gateRoles(handler);

            boolean gated = op.roles.containsValue("deny");
            boolean allPublic = op.roles.values().stream().allMatch("public"::equals);
            if (allPublic || !gated) {
                // self / catalog / group-scoped / public: authorization is
                // service-layer or none — there must be no method-security gate.
                assertThat(actual)
                        .as("%s (%s) must carry no @PreAuthorize", op.key(), op.id())
                        .isNull();
            } else {
                Set<String> expected = new TreeSet<>();
                op.roles.forEach((role, token) -> {
                    if (token.equals("allow") || token.equals("allow_org_scoped")) {
                        expected.add(role);
                    }
                });
                assertThat(actual)
                        .as("%s (%s) must be role-gated", op.key(), op.id())
                        .isNotNull();
                assertThat(actual)
                        .as("%s (%s) @PreAuthorize role set", op.key(), op.id())
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void unauthenticatedCallerHitsThePermitAllBoundary() throws Exception {
        for (Op op : loadMatrix().values()) {
            String url = API_PREFIX + PATH_VAR.matcher(op.path).replaceAll("1");
            int status = mockMvc.perform(request(HttpMethod.valueOf(op.method), url))
                    .andReturn().getResponse().getStatus();
            boolean isPublic = op.roles.values().stream().allMatch("public"::equals);
            if (isPublic) {
                assertThat(status)
                        .as("public op %s (%s) must not require auth", op.key(), op.id())
                        .isNotEqualTo(401);
            } else {
                assertThat(status)
                        .as("protected op %s (%s) must reject anonymous with 401", op.key(), op.id())
                        .isEqualTo(401);
            }
        }
    }
}
