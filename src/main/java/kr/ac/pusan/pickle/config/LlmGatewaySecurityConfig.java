package kr.ac.pusan.pickle.config;

import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.llm.LlmGatewayAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dedicated security chain for the LLM gateway surface
 * ({@code /internal/llm/**}). Matched after the relay chain
 * ({@link RelaySecurityConfig}) and strictly ahead of the broad
 * {@code /internal/**} catch-all in {@link InternalSecurityConfig}, and shares
 * nothing with either — no sshgw token, no sshgw source pin, no shared rate
 * bucket. Access is decided solely by {@link LlmGatewayAuthFilter} (sub-path
 * allowlist + source pin + static bearer with rotation overlap + per-sub-path
 * rate bucket and body cap); everything it lets through is already authorized,
 * so the chain itself permits all.
 */
@Configuration
public class LlmGatewaySecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain llmGatewaySecurityFilterChain(HttpSecurity http,
            LlmGatewayProperties llmGatewayProperties, RateLimitService rateLimitService,
            ProblemJsonWriter problemJsonWriter) throws Exception {
        LlmGatewayAuthFilter authFilter = new LlmGatewayAuthFilter(llmGatewayProperties,
                rateLimitService, problemJsonWriter);
        http
                .securityMatcher("/internal/llm/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
