package kr.ac.pusan.pickle.relay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.RelayProperties;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Confines the relay peers to their sync surface: a relay's tunnel address
 * must reach only {@code /internal/relays/**} — its route into the api host
 * exists solely for the sync call, but the tunnel delivers it to the whole
 * :8080 listener (every chain, actuator included). Registered ahead of the
 * Spring Security filter-chain proxy so no other chain ever evaluates a
 * request from a restricted source; requests to the sync surface pass through
 * untouched and still face {@link RelayAuthFilter} in full.
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
                && !request.getRequestURI().startsWith(RELAY_SURFACE_PREFIX)) {
            problemJsonWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCodes.ACCESS_DENIED, "접근이 거부되었습니다", "허용되지 않은 접근입니다.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
