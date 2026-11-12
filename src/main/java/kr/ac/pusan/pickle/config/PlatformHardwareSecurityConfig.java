package kr.ac.pusan.pickle.config;

import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.observation.PlatformHardwareAuthFilter;
import kr.ac.pusan.pickle.observation.PlatformHardwareProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class PlatformHardwareSecurityConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    SecurityFilterChain platformHardwareSecurityFilterChain(HttpSecurity http, PlatformHardwareProperties properties,
            RateLimitService rate, ProblemJsonWriter writer, Environment environment) throws Exception {
        http.securityMatcher("/internal/platform-hardware-observations", "/internal/platform-hardware-observations/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new PlatformHardwareAuthFilter(properties, rate, writer, environment),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
