package kr.ac.pusan.pickle.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Session-scoped password re-verification points (2FA enrollment/disable/recovery
 * regeneration, password change, withdrawal) share the login rate limiter: the dual-key sliding
 * window bounds the attempt rate, and a mismatch feeds the same escalating account
 * lockout as a failed login. Threat model: a hijacked session brute-forcing the
 * account password against an authenticated endpoint.
 * Distinct client IPs per test so windows do not bleed into other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PasswordReverificationRateLimitTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";
    private static final String WRONG_PASSWORD = "totally-wrong-1!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    @Test
    void mfaEnrollmentStartIsSlidingWindowLimited() throws Exception {
        User user = createActiveUser("reverify.begin.window@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.0.1";

        // a repeated begin just overwrites the un-activated secret, so the only
        // thing that stops the 11th call is the window.
        for (int i = 0; i < 10; i++) {
            postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD), ip)
                    .andExpect(status().isOk());
        }
        postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void mfaEnrollmentPasswordFailuresLockTheAccountOut() throws Exception {
        User user = createActiveUser("reverify.begin.lockout@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.1.1";

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/mfa/totp", access, Map.of("password", WRONG_PASSWORD), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        }
        // the shared lockout counter now blocks login with the correct password
        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("10.97.1.2");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void passwordChangeFailuresLockTheAccountOut() throws Exception {
        User user = createActiveUser("reverify.change.lockout@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.4.1";

        for (int i = 0; i < 5; i++) {
            putJson("/api/v1/me/password", access,
                    Map.of("currentPassword", WRONG_PASSWORD, "newPassword", "An0ther-good-pw!"), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        }
        // the counter is shared, so switching endpoints does not buy more guesses
        postJson("/api/v1/me/withdraw", access, Map.of("password", PASSWORD), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void withdrawPasswordFailuresLockTheAccountOut() throws Exception {
        User user = createActiveUser("reverify.withdraw.lockout@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.2.1";

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/withdraw", access, Map.of("password", WRONG_PASSWORD), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        }
        // even the correct password is refused while the account is locked, so the
        // password-only oracle stops answering
        postJson("/api/v1/me/withdraw", access, Map.of("password", PASSWORD), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // and the account survived the attempt
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void mfaDisableIsSlidingWindowLimited() throws Exception {
        User user = createActiveUser("reverify.disable.window@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.5.1";

        // not enrolled, so every call ends in 409 — but the limiter runs before the
        // enrollment/password checks, so the 11th attempt is refused outright.
        for (int i = 0; i < 10; i++) {
            postJson("/api/v1/me/mfa/disable", access,
                    Map.of("password", PASSWORD, "code", "000000"), ip)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
        }
        postJson("/api/v1/me/mfa/disable", access,
                Map.of("password", PASSWORD, "code", "000000"), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void passwordChangeIsAllowedWhileLoginIsLockedOut() throws Exception {
        User user = createActiveUser("reverify.change.locked@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.7.1";

        for (int i = 0; i < 5; i++) {
            loginWith(user.getEmail(), WRONG_PASSWORD, ip)
                    .andExpect(status().isUnauthorized());
        }
        login(user.getEmail(), ip).andExpect(status().isTooManyRequests());

        // the locked-out account can still change its password from a live
        // session — the remediation must not be blocked by the attack
        putJson("/api/v1/me/password", access,
                Map.of("currentPassword", PASSWORD, "newPassword", "An0ther-good-pw!"), ip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        // and the successful re-verification lifted the lockout
        loginWith(user.getEmail(), "An0ther-good-pw!", ip).andExpect(status().isOk());
    }

    @Test
    void recoveryCodeRegenerationIsSlidingWindowLimited() throws Exception {
        User user = createActiveUser("reverify.regen.window@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.97.3.1";

        // not enrolled, so every call ends in 409 — but the limiter runs before the
        // enrollment/password checks, so the 11th attempt is still refused outright.
        for (int i = 0; i < 10; i++) {
            postJson("/api/v1/me/mfa/recovery-codes", access,
                    Map.of("password", PASSWORD, "code", "000000"), ip)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
        }
        postJson("/api/v1/me/mfa/recovery-codes", access,
                Map.of("password", PASSWORD, "code", "000000"), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private ResultActions login(String email, String ip) throws Exception {
        return loginWith(email, PASSWORD, ip);
    }

    private ResultActions loginWith(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "재검증");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private ResultActions putJson(String path, String access, Map<String, Object> body, String ip)
            throws Exception {
        return mockMvc.perform(put(path)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJson(String path, String access, Map<String, Object> body, String ip)
            throws Exception {
        return mockMvc.perform(post(path)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
