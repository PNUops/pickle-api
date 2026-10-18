package kr.ac.pusan.pickle.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless bearer-token authentication. The {@code token_version} claim is
 * checked against the DB on every request so password change / admin disable
 * invalidates outstanding tokens immediately.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final InstitutionActorProvider actors;

    public JwtAuthenticationFilter(JwtService jwtService, InstitutionActorProvider actors) {
        this.jwtService = jwtService;
        this.actors = actors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        UUID userId;
        Integer tokenVersion;
        try {
            Claims claims = jwtService.parse(token);
            // A subject that is not a UUID throws IllegalArgumentException, which
            // the catch below already treats as an invalid token.
            userId = UUID.fromString(claims.getSubject());
            tokenVersion = claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class);
            if (tokenVersion == null) {
                return;
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // Invalid/expired token: proceed unauthenticated; the entry point
            // renders 401 AUTH_TOKEN_INVALID for protected endpoints.
            return;
        }
        actors.authenticate(userId, tokenVersion).ifPresent(actor -> setAuthentication(request, actor));
    }

    private void setAuthentication(HttpServletRequest request, AuthenticatedUser principal) {
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

}
