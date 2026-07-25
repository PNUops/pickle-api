package kr.ac.pusan.pickle.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.group.PersonalGroupService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Withdrawal requires a valid 2FA code when the account is enrolled. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaWithdrawTest {

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
    private JwtService jwtService;
    @Autowired
    private MfaService mfaService;
    @Autowired
    private TotpService totpService;
    @Autowired
    private PersonalGroupService personalGroupService;

    @Test
    void enrolledWithdrawRequiresValidCode() throws Exception {
        User user = createActiveUser("mfa.withdraw@pusan.ac.kr");
        personalGroupService.ensurePersonalGroup(user);
        String secret = enroll(user.getId());
        String access = jwtService.createAccessToken(user);

        // password only (2FA enrolled, no code) → 403 AUTH_MFA_CODE_INVALID
        postJson("/api/v1/me/withdraw", access, Map.of("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));

        // wrong code → 403
        Map<String, Object> wrong = new HashMap<>();
        wrong.put("password", PASSWORD);
        wrong.put("totpCode", "000000");
        postJson("/api/v1/me/withdraw", access, wrong)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));

        // valid code → 200, account WITHDRAWN
        Map<String, Object> ok = new HashMap<>();
        ok.put("password", PASSWORD);
        ok.put("totpCode", totpService.generate(secret, Instant.now().getEpochSecond() / 30));
        postJson("/api/v1/me/withdraw", access, ok)
                .andExpect(status().isOk());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.WITHDRAWN);
    }

    private String enroll(long userId) {
        var setup = mfaService.begin(userId, PASSWORD, "10.98.1.1");
        mfaService.activate(userId, totpService.generate(setup.secret(),
                Instant.now().getEpochSecond() / 30), "127.0.0.1");
        return setup.secret();
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "탈퇴2FA");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String access,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
