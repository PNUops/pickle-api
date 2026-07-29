package kr.ac.pusan.pickle.config;

import jakarta.servlet.DispatcherType;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.relay.RelayAuthFilter;
import kr.ac.pusan.pickle.relay.RelayRepository;
import kr.ac.pusan.pickle.relay.RelaySourceRestrictionFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dedicated security chain for the relay sync surface
 * ({@code /internal/relays/**}). Matched ahead of the broad
 * {@link InternalSecurityConfig} chain and shares nothing with it — no sshgw
 * token, no sshgw source pin, no shared rate-limit bucket. Access is decided
 * solely by {@link RelayAuthFilter} (path-bound relay row + source pin +
 * per-relay hashed token + per-relay rate limit + body cap); everything it
 * lets through is already authorized, so the chain itself permits all.
 *
 * <p>Also registers {@link RelaySourceRestrictionFilter} ahead of the Spring
 * Security filter-chain proxy: the relay's tunnel address must reach only its
 * sync surface, so requests from a restricted source to any other path (the
 * public API, actuator, every other chain) answer 403 before any chain
 * runs.</p>
 */
@Configuration
public class RelaySecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain relaySecurityFilterChain(HttpSecurity http,
            RelayRepository relayRepository, RelayProperties relayProperties,
            RateLimitService rateLimitService, ProblemJsonWriter problemJsonWriter)
            throws Exception {
        RelayAuthFilter authFilter = new RelayAuthFilter(relayRepository, relayProperties,
                rateLimitService, problemJsonWriter);
        http
                .securityMatcher("/internal/relays/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    FilterRegistrationBean<RelaySourceRestrictionFilter> relaySourceRestrictionFilter(
            RelayProperties relayProperties, ProblemJsonWriter problemJsonWriter) {
        FilterRegistrationBean<RelaySourceRestrictionFilter> registration =
                new FilterRegistrationBean<>(
                        new RelaySourceRestrictionFilter(relayProperties, problemJsonWriter));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        // Ahead of the Spring Security filter-chain proxy (order -100), so a
        // restricted source is confined before any security chain evaluates.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
