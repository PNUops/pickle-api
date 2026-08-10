package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.auth.RefreshTokenService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * Admin disable revokes lingering refresh tokens: access tokens die on
 * the version bump, but a refresh token left unspent during the disable window
 * must be revoked too, or a later enable would let it resurrect the session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminUserDisableRevokeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RefreshTokenService refreshTokenService;

    private String sysAdminToken;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
    }

    @Test
    void disableRevokesLingeringRefreshTokens() throws Exception {
        User target = ensureActiveUser("adr.target@pusan.ac.kr");
        // A refresh token the user never spends during the disable window.
        RefreshTokenService.IssuedToken issued =
                refreshTokenService.issue(target.getId(), Duration.ofDays(14), null, "ua", "127.0.0.1");
        assertThat(refreshTokenService.findByRawToken(issued.rawToken()).orElseThrow().isRevoked())
                .isFalse();

        mockMvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/disable")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "세션 무효화 확인"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // The lingering token is revoked, so a later enable cannot resurrect it.
        assertThat(refreshTokenService.findByRawToken(issued.rawToken()).orElseThrow().isRevoked())
                .isTrue();
    }

    private User ensureActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "세션있음");
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
