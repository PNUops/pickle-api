package kr.ac.pusan.pickle.mfa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Admin-tier 2FA enrollment enforcement is ON (prod behaviour). */
@SpringBootTest(properties = "pickle.mfa.enforce-admin=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaEnforcementTest {

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
    void unenrolledAdminIsScopeRestrictedButCanReachEnrollmentSurfaces() throws Exception {
        User admin = createUser("mfa.enforce.admin@pusan.ac.kr", UserRole.SYS_ADMIN);
        String token = jwtService.createAccessToken(admin);

        // a normal endpoint is blocked 403 MFA_ENROLLMENT_REQUIRED
        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

        // but /me (profile) and the mfa surfaces stay reachable
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));

        // once enrolled, the same endpoint works
        enroll(admin.getId());
        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void plainUserIsNeverRestricted() throws Exception {
        User user = createUser("mfa.enforce.user@pusan.ac.kr", UserRole.USER);
        mockMvc.perform(get("/api/v1/workspaces")
                .header("Authorization", "Bearer " + jwtService.createAccessToken(user)))
                .andExpect(status().isOk());
    }

    private void enroll(long userId) {
        var setup = mfaService.begin(userId, PASSWORD, "10.98.4.1");
        mfaService.activate(userId, totpService.generate(setup.secret(),
                Instant.now().getEpochSecond() / 30), "127.0.0.1");
    }

    private User createUser(String email, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "강제대상");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
