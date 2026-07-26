package kr.ac.pusan.pickle.config;

import java.util.List;

/**
 * The single list of request patterns served without authentication.
 *
 * <p>{@link SecurityConfig} turns these into its {@code permitAll} rules and
 * the OpenAPI configuration turns the same patterns into the per-operation
 * empty security requirement ({@code security: []}) that tells a spec reader
 * which operations need no bearer token. Keeping one list is what stops the
 * runtime rule and the published contract from drifting apart.</p>
 *
 * <p>Patterns are absolute request paths (the API operations keep the
 * {@code /api/v1} prefix here) and use Ant syntax, so they can be matched
 * against generated spec path keys as well as incoming requests.</p>
 */
public final class PublicEndpoints {

    /** Public regardless of HTTP method. */
    public static final List<String> ANY_METHOD = List.of(
            "/api/v1/auth/**",
            // Public system status poll (maintenance banner before login).
            "/api/v1/meta/status",
            "/api/v1/openapi", "/api/v1/openapi/**",
            "/swagger-ui.html", "/swagger-ui/**",
            "/actuator/health", "/actuator/health/**");

    /** Public for GET only: the terms documents shown during signup. */
    public static final List<String> GET_ONLY = List.of(
            "/api/v1/meta/terms", "/api/v1/meta/terms/**");

    private PublicEndpoints() {
    }

    /** Patterns as an array, for the varargs matcher APIs. */
    public static String[] anyMethodPatterns() {
        return ANY_METHOD.toArray(String[]::new);
    }

    /** Patterns as an array, for the varargs matcher APIs. */
    public static String[] getOnlyPatterns() {
        return GET_ONLY.toArray(String[]::new);
    }
}
