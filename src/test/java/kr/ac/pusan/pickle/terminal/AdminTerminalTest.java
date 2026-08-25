package kr.ac.pusan.pickle.terminal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin web-terminal surface (contract v0.10.0): the live-session list scoped by
 * tier (SYS all / ORG own-org only), and SYS_ADMIN-only force-terminate — its
 * role gate, bridge relay, idempotency, and {@code terminal.force_terminate}
 * audit. The bridge control port is a WireMock stub.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminTerminalTest {

    private static WireMockServer bridge;

    @DynamicPropertySource
    static void bridgeProperties(DynamicPropertyRegistry registry) {
        bridge = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        bridge.start();
        bridge.stubFor(post(urlEqualTo("/control/terminate"))
                .willReturn(aResponse().withStatus(204)));
        registry.add("pickle.terminal.bridge-control-base-url",
                () -> "http://localhost:" + bridge.port());
        registry.add("pickle.terminal.bridge-control-token", () -> "test-terminal-control-token");
    }

    @AfterAll
    static void stopBridge() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TerminalSessionRegistry sessionRegistry;

    private String orgAdminToken;
    private String sysAdminToken;
    private String userToken;
    private User sysAdmin;
    private long orgA;
    private long orgB;

    @BeforeEach
    void setUp() {
        sessionRegistry.all().forEach(s -> sessionRegistry.remove(s.sessionId()));
        jdbcTemplate.update("delete from audit_logs where action = 'terminal.force_terminate'");
        bridge.resetRequests();
        orgA = SeedFixtures.seedOrgId(jdbcTemplate);
        orgB = ensureOrg("term-admin-orgb");
        User orgAdmin = ensureUser("term.admin.orga@pusan.ac.kr", "기관관리자", UserRole.ORG_ADMIN, orgA);
        sysAdmin = ensureUser("term.admin.sys@pusan.ac.kr", "시스템관리자", UserRole.SYS_ADMIN, null);
        User plain = ensureUser("term.admin.user@pusan.ac.kr", "일반사용자", UserRole.USER, null);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        userToken = jwtService.createAccessToken(plain);
    }

    @Test
    void listIsFullForEveryAdminTier() throws Exception {
        String sessionA = startedSession(orgA);
        String sessionB = startedSession(orgB);

        // ORG_ADMIN of orgA sees both: every admin tier reads every org
        // (2026-08-25). Ending a session is still an org-scoped write.
        mockMvc.perform(get("/api/v1/admin/terminal-sessions")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionA + "')]").exists())
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionB + "')]").exists());

        // SYS_ADMIN sees both.
        mockMvc.perform(get("/api/v1/admin/terminal-sessions")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionA + "')]").exists())
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionB + "')]").exists());
    }

    @Test
    void listRejectsPlainUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/terminal-sessions")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminateIsSysAdminOnly() throws Exception {
        String sessionA = startedSession(orgA);
        // ORG_ADMIN passes the class gate but not the method gate → 403.
        terminate(orgAdminToken, sessionA).andExpect(status().isForbidden());
        // plain USER is blocked at the class gate → 403.
        terminate(userToken, sessionA).andExpect(status().isForbidden());
    }

    @Test
    void terminateRelaysToBridgeAndAuditsWhenKnown() throws Exception {
        String sessionId = startedSession(orgA);
        terminate(sysAdminToken, sessionId).andExpect(status().isNoContent());

        bridge.verify(1, postRequestedFor(urlEqualTo("/control/terminate")));
        // The mirror's VM id belongs to no row here, so the session is what
        // identifies this audit entry — which is what the detail carries.
        Long auditActor = jdbcTemplate.queryForObject(
                "select actor_id from audit_logs where action = 'terminal.force_terminate' "
                        + "and detail ->> 'sessionId' = ?", Long.class, sessionId);
        assertThat(auditActor).isEqualTo(sysAdmin.getId());
    }

    @Test
    void terminateUnknownSessionIsIdempotentNoAudit() throws Exception {
        String ghost = UUID.randomUUID().toString();
        terminate(sysAdminToken, ghost).andExpect(status().isNoContent());
        // bridge still receives the (idempotent) call...
        bridge.verify(1, postRequestedFor(urlEqualTo("/control/terminate")));
        // ...but no audit row is written for an unknown session.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'terminal.force_terminate'",
                Long.class)).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions terminate(String token,
            String sessionId) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/admin/terminal-sessions/" + sessionId + "/terminate")
                .header("Authorization", "Bearer " + token));
    }

    /** Registers a started mirror session for the given org and returns its id. */
    private String startedSession(long orgId) {
        String sessionId = UUID.randomUUID().toString();
        long vmId = 800_000L + Math.abs(sessionId.hashCode() % 100_000);
        sessionRegistry.registerPending(sessionId, sysAdmin.getId(), UserRole.USER, vmId, orgId, 0);
        sessionRegistry.markStarted(sessionId, "203.0.113.9");
        return sessionId;
    }

    private long ensureOrg(String name) {
        Long existing = jdbcTemplate.query("select id from orgs where name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, name);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject(
                "insert into orgs (name) values (?) returning id", Long.class, name);
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
            return saved;
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
