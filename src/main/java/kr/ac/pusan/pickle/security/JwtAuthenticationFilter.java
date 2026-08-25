package kr.ac.pusan.pickle.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserOrgRole;
import kr.ac.pusan.pickle.user.UserOrgRoleRepository;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
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
    private final UserRepository userRepository;
    private final UserOrgRoleRepository userOrgRoleRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
            UserOrgRoleRepository userOrgRoleRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userOrgRoleRepository = userOrgRoleRepository;
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
        try {
            Claims claims = jwtService.parse(token);
            // A subject that is not a UUID throws IllegalArgumentException, which
            // the catch below already treats as an invalid token.
            UUID userId = UUID.fromString(claims.getSubject());
            Integer tokenVersion = claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class);
            if (tokenVersion == null) {
                return;
            }
            userRepository.findByPublicId(userId)
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .filter(user -> user.getTokenVersion() == tokenVersion)
                    .ifPresent(user -> setAuthentication(request, user));
        } catch (JwtException | IllegalArgumentException ignored) {
            // Invalid/expired token: proceed unauthenticated; the entry point
            // renders 401 AUTH_TOKEN_INVALID for protected endpoints.
        }
    }

    private void setAuthentication(HttpServletRequest request, User user) {
        AuthenticatedUser principal =
                new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(),
                        user.getRole(), orgRolesOf(user));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * The organisations this account administers, read per request like the
     * token version beside it. Deliberately not a JWT claim: the org_id claim
     * was dropped in V78 so a decoded token discloses no internal ids, and
     * reading here means revoking someone's org takes effect on their next
     * request rather than when their access token happens to expire.
     *
     * <p>Only the org tier is looked up, so the ordinary user's request pays
     * nothing for this.
     */
    private Map<Long, UserRole> orgRolesOf(User user) {
        if (!user.getRole().isOrgTier()) {
            return Map.of();
        }
        return userOrgRoleRepository.findByUserIdOrderByOrgIdAsc(user.getId()).stream()
                .collect(Collectors.toMap(UserOrgRole::getOrgId, UserOrgRole::getRole,
                        (first, second) -> first));
    }
}
