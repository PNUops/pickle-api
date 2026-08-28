package kr.ac.pusan.pickle.mfa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wrong second-factor codes are throttled on their own counter. Someone whose
 * authenticator device is gone types recovery codes from memory and gets them
 * wrong: that must slow the code attempts down without also locking the account
 * out of logging in, which would take away the very session they would recover
 * from. Distinct client IPs per test so the sliding windows do not bleed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaCodeLockoutTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";
    private static final String WRONG_RECOVERY_CODE = "aaaa-bbbb-cccc";

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
    void disableCodeFailuresThrottleCodesWithoutLockingLogin() throws Exception {
        User user = createActiveUser("mfa.codelock.disable@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        enroll(access, "10.98.0.1");
        String ip = "10.98.0.2";

        // password correct, recovery code wrong, five times over
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/mfa/disable", access,
                    Map.of("password", PASSWORD, "recoveryCode", WRONG_RECOVERY_CODE), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
        }
        // further code attempts are locked out
        postJson("/api/v1/me/mfa/disable", access,
                Map.of("password", PASSWORD, "recoveryCode", WRONG_RECOVERY_CODE), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // ...while login, on its own counter, still answers the 2FA challenge
        login(user.getEmail(), "10.98.0.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test
    void recoveryCodeRegenerationCodeFailuresDoNotLockLogin() throws Exception {
        User user = createActiveUser("mfa.codelock.regen@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        enroll(access, "10.98.1.1");
        String ip = "10.98.1.2";

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/mfa/recovery-codes", access,
                    Map.of("password", PASSWORD, "code", "000000"), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
        }
        login(user.getEmail(), "10.98.1.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test
    void activateCodeFailuresAreThrottledOnTheCodeLockout() throws Exception {
        User user = createActiveUser("mfa.codelock.activate@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        String ip = "10.98.4.1";

        // begin enrollment, then feed wrong activation codes: the endpoint used to have
        // no throttle at all, which made it the one unbounded code path.
        postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD), ip)
                .andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", "000000"), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
        }
        postJson("/api/v1/me/mfa/totp/activate", access, Map.of("code", "000000"), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // login stays on its own counter
        login(user.getEmail(), "10.98.4.2").andExpect(status().isOk());
    }

    @Test
    void withdrawCodeFailuresDoNotLockLogin() throws Exception {
        User user = createActiveUser("mfa.codelock.withdraw@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        enroll(access, "10.98.3.1");
        String ip = "10.98.3.2";

        // withdrawal re-verifies the same two factors, so it follows the same rule:
        // a wrong recovery code must not lock the account out of logging in.
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/withdraw", access,
                    Map.of("password", PASSWORD, "recoveryCode", WRONG_RECOVERY_CODE), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
        }
        login(user.getEmail(), "10.98.3.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test
    void loginStageTwoIsBoundedAcrossAddressesByTheAccountWindow() throws Exception {
        User user = createActiveUser("mfa.codelock.spread@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        enroll(access, "10.98.6.1");

        // One challenge is enough: a wrong code does not consume it. The lockout is
        // keyed on (account, address), so an attacker who spreads over addresses gets
        // a fresh five each time and the lockout never fires. What has to stop that is
        // the account-wide window, which this endpoint did not have.
        MvcResult challenge = login(user.getEmail(), "10.98.6.2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn();
        String mfaToken = json(challenge).get("mfaToken").asText();

        for (int i = 0; i < 10; i++) {
            completeMfa(mfaToken, "10.98.7." + i).andExpect(status().isUnauthorized());
        }
        // eleventh in the same minute, from an eleventh address the lockout has never
        // seen: only the account window can refuse this one
        completeMfa(mfaToken, "10.98.7.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void disablePasswordFailuresStillLockTheGuessingClientOutOfLogin() throws Exception {
        User user = createActiveUser("mfa.codelock.password@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        enroll(access, "10.98.2.1");
        String ip = "10.98.2.2";

        // a wrong password is a password failure wherever it is presented, so it
        // keeps feeding the login lockout
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/me/mfa/disable", access,
                    Map.of("password", "totally-wrong-1!", "recoveryCode", WRONG_RECOVERY_CODE), ip)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_MFA_CODE_INVALID"));
        }
        login(user.getEmail(), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // the lockout is keyed on (account, client address), so it reaches only the
        // client that was guessing — the account's other clients still log in
        login(user.getEmail(), "10.98.2.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    /** begin + activate, so the account ends up enrolled with live recovery codes. */
    private void enroll(String access, String ip) throws Exception {
        MvcResult begun = postJson("/api/v1/me/mfa/totp", access, Map.of("password", PASSWORD), ip)
                .andExpect(status().isOk()).andReturn();
        String secret = json(begun).get("secret").asText();
        postJson("/api/v1/me/mfa/totp/activate", access,
                Map.of("code", totpService.generate(secret, Instant.now().getEpochSecond() / 30)), ip)
                .andExpect(status().isOk());
    }

    /** Login stage 2 with a deliberately wrong recovery code, from the given address. */
    private ResultActions completeMfa(String mfaToken, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("mfaToken", mfaToken, "recoveryCode", WRONG_RECOVERY_CODE))));
    }

    private ResultActions login(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD))));
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "코드잠금");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
