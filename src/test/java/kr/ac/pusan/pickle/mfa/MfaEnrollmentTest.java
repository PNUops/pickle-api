package kr.ac.pusan.pickle.mfa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** M6 W2-A: 2FA enrollment / activation / disable / recovery-code regeneration end-to-end. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaEnrollmentTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";

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
    @Autowired
    private TotpService totpService;

    @Test
    void fullEnrollmentActivationAndRecoveryCodeDisable() throws Exception {
        User user = createActiveUser("mfa.enroll@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);

        // mfaEnabled starts false
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));

        // begin with wrong password → 403; activate before begin → 409
        postJson("/api/v1/me/mfa/totp", access, Map.of("password", "totally-wrong-1!"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", "000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_SETUP_NOT_IN_PROGRESS"));

        // begin → secret + otpauth URI
        MvcResult begun = postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/Pickle:")))
                .andReturn();
        String secret = json(begun).get("secret").asText();

        // activate with a wrong code → 403; still not enrolled
        postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", "000000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));

        // activate with the real code → 10 recovery codes, now enrolled
        MvcResult activated = postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", codeFor(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        String recoveryCode = json(activated).get("recoveryCodes").get(0).asText();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        // begin again while enrolled → 409 MFA_ALREADY_ENROLLED
        postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_ALREADY_ENROLLED"));

        // disable: exactly-one rule (both code and recoveryCode) → 422
        Map<String, Object> both = new HashMap<>();
        both.put("password", PASSWORD);
        both.put("code", codeFor(secret));
        both.put("recoveryCode", recoveryCode);
        postJson("/api/v1/me/mfa/disable", access, both)
                .andExpect(status().isUnprocessableEntity());

        // disable with a recovery code → 200, back to unenrolled
        postJson("/api/v1/me/mfa/disable", access,
                Map.of("password", PASSWORD, "recoveryCode", recoveryCode))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.mfaEnabled").value(false));

        // disable when not enrolled → 409 MFA_NOT_ENROLLED
        postJson("/api/v1/me/mfa/disable", access, Map.of("password", PASSWORD, "code", "000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
    }

    @Test
    void regenerateRecoveryCodesInvalidatesPriorCodes() throws Exception {
        User user = createActiveUser("mfa.regen@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        MvcResult begun = postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        String secret = json(begun).get("secret").asText();
        MvcResult activated = postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", codeFor(secret)))
                .andExpect(status().isOk()).andReturn();
        String oldRecoveryCode = json(activated).get("recoveryCodes").get(0).asText();

        // regenerate → new set; old code no longer works to disable
        postJson("/api/v1/me/mfa/recovery-codes", access,
                Map.of("password", PASSWORD, "code", codeFor(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
        postJson("/api/v1/me/mfa/disable", access,
                Map.of("password", PASSWORD, "recoveryCode", oldRecoveryCode))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
    }

    private String codeFor(String secret) {
        return totpService.generate(secret, Instant.now().getEpochSecond() / 30);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "이중인증");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private ResultActions postJson(String path, String access, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
