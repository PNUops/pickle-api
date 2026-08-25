package kr.ac.pusan.pickle.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * An account with no password at all — what a Google-only account will be once
 * that path exists. V89 made {@code users.password_hash} nullable, so these are
 * the behaviours that stop a null from leaking either an exception or an
 * answer.
 *
 * <p>The login case is the one that matters. Before the guard, a null hash
 * reached the encoder and answered 500, and that 500 told an anonymous caller
 * which addresses are passwordless — the exact question the uniform 401 exists
 * to refuse. Everything else here is a dead end the account holder has to be
 * able to read, so those answer 409 by name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PasswordlessAccountTest {

    private static final String EMAIL = "passwordless.tester@pusan.ac.kr";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private String accessToken;

    @BeforeEach
    void createPasswordlessAccount() throws Exception {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);
        User user = new User(EMAIL, null, "무비번");
        user.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        accessToken = null;
    }

    @Test
    void loginAnswersTheUniformUnauthorizedRatherThanFailingOnANullHash() throws Exception {
        // Any password at all: the account has none, so none can match. The
        // response has to be indistinguishable from a wrong password on an
        // ordinary account — same status, same code.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", EMAIL, "password", "Corr3ct-horse-battery!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void changingThePasswordSaysThereIsNoneRatherThanThatItIsWrong() throws Exception {
        mockMvc.perform(put("/api/v1/me/password")
                        .header("Authorization", "Bearer " + issueAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "anything-at-all",
                                "newPassword", "purple-Monkey-dishwasher9"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_SET"));
    }

    @Test
    void sudoReverificationSaysThereIsNoPasswordRatherThanRejectingForever() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reverify")
                        .header("Authorization", "Bearer " + issueAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "anything-at-all"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_SET"));
    }

    @Test
    void withdrawalSaysThereIsNoPassword() throws Exception {
        mockMvc.perform(post("/api/v1/me/withdraw")
                        .header("Authorization", "Bearer " + issueAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "anything-at-all"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_SET"));
    }

    @Test
    void enrollingInTwoFactorSaysThereIsNoPassword() throws Exception {
        mockMvc.perform(post("/api/v1/me/mfa/totp")
                        .header("Authorization", "Bearer " + issueAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "anything-at-all"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_SET"));
    }

    /**
     * The account cannot log in, so the token comes from the same place a
     * Google login will eventually put it: minted directly for this user.
     */
    private String issueAccessToken() {
        if (accessToken == null) {
            accessToken = jwtService.createAccessToken(userRepository.findByEmail(EMAIL).orElseThrow());
        }
        return accessToken;
    }
}
