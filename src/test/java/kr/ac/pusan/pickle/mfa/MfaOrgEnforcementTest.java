package kr.ac.pusan.pickle.mfa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Org-tier 2FA enrollment enforcement is ON alongside the system tier. */
@SpringBootTest(properties = {"pickle.mfa.enforce-admin=true", "pickle.mfa.enforce-org-admin=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MfaOrgEnforcementTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";
    private static final UserRole[] ORG_TIER = {UserRole.ORG_ADMIN, UserRole.ORG_MANAGER, UserRole.ORG_VIEWER};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private MfaService mfaService;
    @Autowired
    private TotpService totpService;
    @Autowired
    private InstitutionActorProvider actors;
    @Autowired
    private LifecycleActors lifecycleActors;

    @Test
    void everyUnenrolledOrgRoleIsScopeRestrictedButCanReachEnrollment() throws Exception {
        for (UserRole role : ORG_TIER) {
            User admin = createUser("mfa.org.enforce." + role.name().toLowerCase() + "@pusan.ac.kr", role);
            String token = jwtService.createAccessToken(admin);

            mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

            mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mfaEnabled").value(false));
        }
    }

    @Test
    void anEnrolledOrgAdminIsNotRestricted() throws Exception {
        User admin = createUser("mfa.org.enforce.enrolled@pusan.ac.kr", UserRole.ORG_ADMIN);
        enroll(admin.getId());
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void theOrdinaryUserIsStillNeverRestricted() throws Exception {
        User user = createUser("mfa.org.enforce.user@pusan.ac.kr", UserRole.USER);
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(user)))
                .andExpect(status().isOk());
    }

    /**
     * The service-layer recheck reads the current role, so an account promoted
     * into the org tier after its principal was captured is held to the
     * requirement on its next protected write, not only by the request filter.
     */
    @Test
    void theFreshActorRecheckCoversAnAccountPromotedIntoTheOrgTier() {
        User user = createUser("mfa.org.enforce.promoted@pusan.ac.kr", UserRole.USER);
        var captured = actors.authenticate(user.getPublicId(), user.getTokenVersion()).orElseThrow();
        user.setRole(UserRole.ORG_MANAGER);
        userRepository.saveAndFlush(user);
        assertThatThrownBy(() -> lifecycleActors.current(captured))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("MFA_ENROLLMENT_REQUIRED");
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
