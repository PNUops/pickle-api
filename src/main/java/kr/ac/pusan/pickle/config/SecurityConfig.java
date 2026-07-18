package kr.ac.pusan.pickle.config;

import jakarta.servlet.DispatcherType;
import kr.ac.pusan.pickle.security.JwtAuthenticationFilter;
import kr.ac.pusan.pickle.security.MaintenanceModeFilter;
import kr.ac.pusan.pickle.security.ProblemAccessDeniedHandler;
import kr.ac.pusan.pickle.security.ProblemAuthenticationEntryPoint;
import kr.ac.pusan.pickle.security.RefreshCsrfFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless bearer-token security (docs/plan/07). Spring's session-based CSRF
 * protection stays disabled for the pure-Bearer API; the two cookie-authed
 * endpoints (refresh/logout) are instead guarded by {@link RefreshCsrfFilter}
 * (double-submit cookie), on top of the /api/v1/auth-scoped SameSite=Lax
 * refresh cookie and refresh-token rotation.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            MaintenanceModeFilter maintenanceModeFilter,
            RefreshCsrfFilter refreshCsrfFilter,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Public system status poll (contract getSystemStatus, security: []).
                        .requestMatchers("/api/v1/meta/status").permitAll()
                        .requestMatchers("/api/v1/openapi", "/api/v1/openapi/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Maintenance gate runs right after auth so the principal's role
                // is known (admin tier bypasses the 503; exempt paths always pass).
                .addFilterAfter(maintenanceModeFilter, JwtAuthenticationFilter.class)
                // CSRF check first: refresh/logout requests are rejected before
                // any authentication work; independent of the JWT filter.
                .addFilterBefore(refreshCsrfFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /** BCrypt cost 12 (docs/plan/07). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
