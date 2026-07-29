package kr.ac.pusan.pickle.relay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.RelayProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Confines the relay peers to their sync surface: a relay's tunnel address
 * must reach only {@code /internal/relays/**} — its route into the api host
 * exists solely for the sync call, but the tunnel delivers it to the whole
 * :8080 listener (every chain, actuator included). Registered ahead of the
 * Spring Security filter-chain proxy so no other chain ever evaluates a
 * request from a restricted source; requests to the sync surface pass through
 * untouched and still face {@link RelayAuthFilter} in full.
 *
 * <p>The confinement decides on the <b>normalized</b> path (percent-decoded,
 * dot-segments collapsed), and anything that fails to normalize cleanly —
 * traversal that escapes, lingering {@code ..}, path parameters, malformed
 * encoding — counts as NOT the sync surface, so a restricted source gets 403.
 * Deliberately self-contained: the guarantee must not lean on
 * {@code StrictHttpFirewall}, which runs later and only inside the security
 * chain. ERROR-dispatch requests are not re-checked (REQUEST dispatch only) —
 * accepted: an error rendering runs no handler of its own.</p>
 */
public class RelaySourceRestrictionFilter extends OncePerRequestFilter {

    private static final String RELAY_SURFACE_PREFIX = "/internal/relays/";

    private final Set<String> restrictedSources;
    private final ProblemJsonWriter problemJsonWriter;

    public RelaySourceRestrictionFilter(RelayProperties properties,
            ProblemJsonWriter problemJsonWriter) {
        this.restrictedSources = Set.copyOf(properties.restrictedSourceIps());
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (restrictedSources.contains(request.getRemoteAddr())
                && !isRelaySurface(request.getRequestURI())) {
            problemJsonWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCodes.ACCESS_DENIED, "접근이 거부되었습니다", "허용되지 않은 접근입니다.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * True only when the raw URI normalizes cleanly to the sync surface.
     * Fail-closed: undecodable input, path parameters ({@code ;}), a percent
     * sign surviving one decode (double encoding), or any {@code ..} surviving
     * normalization all answer false. Legitimate sync URIs are plain ASCII
     * digits and slashes, so nothing real is ever refused by these rules.
     */
    static boolean isRelaySurface(String rawUri) {
        if (rawUri == null || rawUri.indexOf(';') >= 0) {
            return false;
        }
        String decoded;
        try {
            decoded = UriUtils.decode(rawUri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (decoded.indexOf('%') >= 0) {
            return false; // double-encoded — never decode twice, just refuse
        }
        String cleaned = StringUtils.cleanPath(decoded);
        if (cleaned.contains("..")) {
            return false; // traversal that escaped the root — never a match
        }
        return cleaned.startsWith(RELAY_SURFACE_PREFIX);
    }
}
