package kr.ac.pusan.pickle.config;

import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.sshgw.InternalSshGatewayAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dedicated, highest-precedence security chain for the infra-to-infra
 * {@code /internal/**} surface (docs/api/internal.md, docs/plan/07). It is
 * matched ahead of the user-JWT chain in {@link SecurityConfig} and shares
 * nothing with it: no JWT filter, no user authentication. Access is decided
 * solely by {@link InternalSshGatewayAuthFilter} (source-IP allowlist + static
 * bearer + rate limit); everything it lets through is already authorized, so
 * the chain itself permits all.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
            SshGatewayProperties sshGatewayProperties, RateLimitService rateLimitService,
            ProblemJsonWriter problemJsonWriter) throws Exception {
        InternalSshGatewayAuthFilter authFilter = new InternalSshGatewayAuthFilter(
                sshGatewayProperties, rateLimitService, problemJsonWriter);
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
