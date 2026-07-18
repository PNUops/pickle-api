package kr.ac.pusan.pickle.mfa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.mfa.dto.MfaSetupResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** M6 W2-A: two-stage login (/auth/login → MfaChallengeResponse → /auth/mfa). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaLoginTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    private MfaService mfaService;
    @Autowired
    private TotpService totpService;

    @Test
    void enrolledLoginRequiresSecondFactorThenIssuesTokens() throws Exception {
        User user = createActiveUser("mfa.login@pusan.ac.kr");
        String secret = enroll(user.getId());

        // stage 1: correct password → challenge, no cookies issued
        MvcResult challenged = login(user.getEmail(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaToken").isNotEmpty())
                .andExpect(cookie().doesNotExist("pickle_refresh"))
                .andReturn();
        String mfaToken = json(challenged).get("mfaToken").asText();

        // stage 2 wrong code → 401, token stays usable
        mfa(Map.of("mfaToken", mfaToken, "code", "000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));

        // stage 2 correct code → tokens + cookies
        mfa(Map.of("mfaToken", mfaToken, "code", codeFor(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("pickle_refresh"))
                .andExpect(cookie().exists("pickle_csrf"));

        // the token is single-use: reusing it → 410
        mfa(Map.of("mfaToken", mfaToken, "code", codeFor(secret)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_TOKEN_EXPIRED"));
    }

    @Test
    void recoveryCodeCompletesLoginAndIsSingleUse() throws Exception {
        User user = createActiveUser("mfa.login.recovery@pusan.ac.kr");
        String secret = enroll(user.getId());
        String recoveryCode = mfaService.regenerateRecoveryCodes(user.getId(), PASSWORD,
                codeFor(secret)).recoveryCodes().get(0);

        MvcResult challenged = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();
        String mfaToken = json(challenged).get("mfaToken").asText();
        mfa(Map.of("mfaToken", mfaToken, "recoveryCode", recoveryCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // a fresh challenge with the already-consumed recovery code → 401
        MvcResult again = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();
        mfa(Map.of("mfaToken", json(again).get("mfaToken").asText(), "recoveryCode", recoveryCode))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Enrolls the user in-process and returns the raw Base32 secret. */
    private String enroll(long userId) {
        MfaSetupResponse setup = mfaService.begin(userId, PASSWORD);
        mfaService.activate(userId, codeFor(setup.secret()), "127.0.0.1");
        return setup.secret();
    }

    private String codeFor(String secret) {
        return totpService.generate(secret, Instant.now().getEpochSecond() / 30);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "로그인2FA");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private ResultActions login(String email, String password) throws Exception {
        // Unique X-Forwarded-For per call keeps the per-IP+account login limiter isolated.
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "10." + (int) (Math.random() * 250) + "."
                        + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions mfa(Map<String, Object> body) throws Exception {
        // /auth/mfa has no per-IP limiter (only the per-account lockout), so no XFF needed.
        return mockMvc.perform(post("/api/v1/auth/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
