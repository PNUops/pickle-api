package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * PG counter-table rate limiting: sliding window (10/min per IP+account) and
 * escalating lockout after 5 consecutive login failures (docs/plan/07).
 * Distinct client IPs per test so windows do not bleed into other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void slidingWindowLimitsRequestsPerMinute() throws Exception {
        String ip = "10.99.0.1";
        String email = "rl.window@pusan.ac.kr";

        for (int i = 0; i < 10; i++) {
            postJson("/api/v1/auth/resend-verification", Map.of("email", email), ip)
                    .andExpect(status().isAccepted());
        }

        MvcResult limited = postJson("/api/v1/auth/resend-verification", Map.of("email", email), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 90L);
    }

    @Test
    void consecutiveLoginFailuresLockTheAccount() throws Exception {
        String email = "rl.lockout@pusan.ac.kr";
        String password = "Corr3ct-horse-battery!";
        User user = new User(email, passwordEncoder.encode(password), "잠금테스트");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        // 5 consecutive failures from different IPs (lockout is per account)
        for (int i = 1; i <= 5; i++) {
            postJson("/api/v1/auth/login", Map.of("email", email, "password", "wrong-password-" + i),
                    "10.99.1." + i)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }

        // locked: even the correct password now yields 429 with Retry-After
        MvcResult locked = postJson("/api/v1/auth/login", Map.of("email", email, "password", password),
                "10.99.1.100")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        long retryAfter = Long.parseLong(locked.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 900L);
    }

    private ResultActions postJson(String uri, Map<String, ?> body, String clientIp) throws Exception {
        return mockMvc.perform(post(uri)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
