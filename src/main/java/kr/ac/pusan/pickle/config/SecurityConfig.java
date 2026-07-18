package kr.ac.pusan.pickle.config;

import jakarta.servlet.DispatcherType;
import kr.ac.pusan.pickle.security.JwtAuthenticationFilter;
import kr.ac.pusan.pickle.security.MfaEnrollmentFilter;
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
            MfaEnrollmentFilter mfaEnrollmentFilter,
            RefreshCsrfFilter refreshCsrfFilter,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Public consent documents (contract security: []).
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/meta/terms", "/api/v1/meta/terms/**").permitAll()
                        .requestMatchers("/api/v1/openapi", "/api/v1/openapi/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // MFA enrollment gate (G5) runs right after auth so the principal
                // is resolved; unenrolled admin-tier accounts are scope-restricted.
                .addFilterAfter(mfaEnrollmentFilter, JwtAuthenticationFilter.class)
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
