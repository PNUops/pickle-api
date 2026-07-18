package kr.ac.pusan.pickle.meta;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /meta/status (public) and the maintenance gate (contract v0.9.0). Covers
 * the toggle, the load-bearing exemptions (auth/login, meta, actuator health),
 * admin-tier bypass, non-admin/anonymous 503, and email/message validators.
 * The maintenance flag is flipped straight in the DB and the cache invalidated
 * for deterministic (no-sleep) propagation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MaintenanceModeTest {

    private static final String USER_EMAIL = "mm-user@pickle.local";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SystemStatusService systemStatusService;

    private String sysAdminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("admin@pickle.local").orElseThrow());
        User user = userRepository.findByEmail(USER_EMAIL).orElseGet(() -> {
            User u = new User(USER_EMAIL, passwordEncoder.encode("mm-user-pw!"), "점검 테스트 사용자");
            u.setRole(UserRole.USER);
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerifiedAt(Instant.now());
            return userRepository.save(u);
        });
        userToken = jwtService.createAccessToken(user);
        setMaintenance(false, "");
    }

    @AfterEach
    void restore() {
        setMaintenance(false, "");
    }

    @Test
    void statusIsPublicAndReflectsSettings() throws Exception {
        jdbcTemplate.update("update settings set value = '\"공지입니다\"'::jsonb where key = 'banner_message'");
        jdbcTemplate.update("update settings set value = '\"ops@pickle.local\"'::jsonb where key = 'contact_email'");
        systemStatusService.invalidate();

        mockMvc.perform(get("/api/v1/meta/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance").value(false))
                .andExpect(jsonPath("$.maintenanceMessage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.bannerMessage").value("공지입니다"))
                .andExpect(jsonPath("$.contactEmail").value("ops@pickle.local"));

        jdbcTemplate.update("update settings set value = '\"\"'::jsonb where key = 'banner_message'");
        jdbcTemplate.update("update settings set value = '\"\"'::jsonb where key = 'contact_email'");
    }

    @Test
    void maintenanceOffLetsEveryoneThrough() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    void maintenanceOnBlocksNonAdminWithProblem503() throws Exception {
        setMaintenance(true, "잠시만 기다려 주세요");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAINTENANCE_MODE"))
                .andExpect(jsonPath("$.detail").value("잠시만 기다려 주세요"));
    }

    @Test
    void maintenanceOnUsesDefaultMessageWhenUnset() throws Exception {
        setMaintenance(true, "");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("서비스 점검 중입니다. 잠시 후 다시 이용해 주세요."));
    }

    @Test
    void maintenanceOnBlocksAnonymousOnProtectedPaths() throws Exception {
        setMaintenance(true, "");

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAINTENANCE_MODE"));
    }

    @Test
    void maintenanceOnLetsAdminTierThrough() throws Exception {
        setMaintenance(true, "");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    void exemptPathsAlwaysPassEvenInMaintenance() throws Exception {
        setMaintenance(true, "");

        // Public status poll must keep working (it carries the notice to the UI).
        mockMvc.perform(get("/api/v1/meta/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance").value(true));
        // Admin login path: never gated (bad creds ⇒ 401, not a 503).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.z\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
        // Deploy health poll must never 503 (would roll back a good deploy).
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void togglingOffPropagatesWithinCacheTtl() throws Exception {
        setMaintenance(true, "");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isServiceUnavailable());

        setMaintenance(false, "");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    void contactEmailValidatorRejectsMalformedAndAcceptsEmpty() throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/contact_email")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/admin/settings/contact_email")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"help@pickle.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueType").value("STRING"));
        mockMvc.perform(put("/api/v1/admin/settings/contact_email")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void maintenanceModeKeyAcceptsBooleanOnly() throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/maintenance_mode")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"yes\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/admin/settings/maintenance_mode")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true));
    }

    private void setMaintenance(boolean on, String message) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'maintenance_mode'",
                Boolean.toString(on));
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'maintenance_message'",
                "\"" + message + "\"");
        systemStatusService.invalidate();
    }
}
