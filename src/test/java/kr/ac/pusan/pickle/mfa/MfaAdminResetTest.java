package kr.ac.pusan.pickle.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** SYS_ADMIN 2FA reset (lockout recovery). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaAdminResetTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";

    @Autowired
    private MockMvc mockMvc;
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

    @Test
    void sysAdminResetsEnrollmentAndUnenrolledYields409() throws Exception {
        User sysAdmin = createUser("mfa.reset.admin@pusan.ac.kr", UserRole.SYS_ADMIN);
        User target = createUser("mfa.reset.target@pusan.ac.kr", UserRole.USER);
        String adminToken = jwtService.createAccessToken(sysAdmin);

        // not enrolled yet → 409 MFA_NOT_ENROLLED
        mockMvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/mfa-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));

        enroll(target.getId());
        assertThat(mfaService.isEnrolled(target.getId())).isTrue();

        // reset → 200, enrollment gone
        mockMvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/mfa-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(mfaService.isEnrolled(target.getId())).isFalse();

        // unknown user → 404
        mockMvc.perform(post("/api/v1/admin/users/99999/mfa-reset")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonSysAdminForbidden() throws Exception {
        User plainUser = createUser("mfa.reset.nonadmin@pusan.ac.kr", UserRole.USER);
        User target = createUser("mfa.reset.victim@pusan.ac.kr", UserRole.USER);
        enroll(target.getId());

        mockMvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/mfa-reset")
                .header("Authorization", "Bearer " + jwtService.createAccessToken(plainUser)))
                .andExpect(status().isForbidden());
        assertThat(mfaService.isEnrolled(target.getId())).isTrue();
    }

    private void enroll(long userId) {
        var setup = mfaService.begin(userId, PASSWORD, "10.98.2.1");
        mfaService.activate(userId, totpService.generate(setup.secret(),
                Instant.now().getEpochSecond() / 30), "127.0.0.1");
    }

    private User createUser(String email, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "리셋대상");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
