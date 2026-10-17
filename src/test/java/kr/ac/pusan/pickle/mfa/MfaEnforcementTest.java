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

/** Sys-tier 2FA enrollment enforcement is ON (prod behaviour). */
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
    @Autowired
    private kr.ac.pusan.pickle.request.RequestRecipientCancellationService recipientCancellations;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

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

        // Changing an existing password is NOT one of them. The exemption that
        // used to cover it matched on the path, so it let the change through
        // alongside the retired first-time set; both went when the sudo-mode
        // gate did. Changing a password is not a step in enrolling, so this is
        // the restriction working rather than a lock-out.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/me/password")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"whatever-1!\","
                                + "\"newPassword\":\"new-horse-battery-staple!\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

        // once enrolled, the same endpoint works
        enroll(admin.getId());
        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * The console puts the profile form in front of the whole shell, and the
     * enrolment screen is inside the shell. If this write were refused, an
     * unenrolled admin with no profile could reach neither.
     */
    @Test
    void anUnenrolledAdminCanStillAnswerTheProfileGate() throws Exception {
        User admin = createUser("mfa.enforce.profile@pusan.ac.kr", UserRole.SYS_ADMIN);
        String token = jwtService.createAccessToken(admin);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/me/profile")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"position\":\"STAFF\",\"departmentOther\":\"플랫폼 운영\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(true));

        // The terms gate stands in front of the profile gate, and its write is
        // exempt for the same reason. Not 403 is what is asserted here: the
        // body is empty, so the controller's own answer is a validation error.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/me/consents")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"consents\":[]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // The exemption is those paths, not the /me/ prefix.
        mockMvc.perform(get("/api/v1/me/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
    }

    @Test
    void aPasswordlessAdminCanStillReachThePathThatGivesItAPassword() throws Exception {
        // The lock-out this closes: enrolment needs a password, an account made
        // through Google has none, and the filter used to refuse the endpoint
        // that would give it one. Promoting a Google account to an admin role
        // was enough to reach that state, with no way back out.
        //
        // The route is now the reset mail rather than POST /me/password, which
        // was retired with the sudo-mode gate that was its only authorization.
        // What keeps the lock-out closed is the /api/v1/auth/ exemption.
        User admin = new User("mfa.enforce.google@pusan.ac.kr", null, "구글관리자");
        admin.setRole(UserRole.SYS_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerifiedAt(Instant.now());
        admin = userRepository.saveAndFlush(admin);
        String token = jwtService.createAccessToken(admin);

        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

        // Not 403 MFA_ENROLLMENT_REQUIRED: the filter lets this one through, so
        // the account can still ask for the mail that gives it a password.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/password-reset")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + admin.getEmail() + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
    }

    @Test
    void plainUserIsNeverRestricted() throws Exception {
        User user = createUser("mfa.enforce.user@pusan.ac.kr", UserRole.USER);
        mockMvc.perform(get("/api/v1/workspaces")
                .header("Authorization", "Bearer " + jwtService.createAccessToken(user)))
                .andExpect(status().isOk());
    }

    @Test
    void recipientCancellationRetainsMfaEnforcementOnBothSurfaces() throws Exception {
        User admin = createUser("mfa.enforce.recipient@pusan.ac.kr", UserRole.SYS_ADMIN);
        String token = jwtService.createAccessToken(admin);
        for (String prefix : java.util.List.of("/api/v1/requests/", "/api/v1/admin/requests/")) {
            String target = prefix + java.util.UUID.randomUUID() + "/recipients/" + java.util.UUID.randomUUID();
            mockMvc.perform(get(target + "/cancellation-impact").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(target + "/cancel")
                    .header("Authorization", "Bearer " + token).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
        }
    }

    @Test
    void currentSystemRoleRechecksEnrollmentAfterTheHttpPrincipalWasCaptured() {
        User user = createUser("mfa.enforce.promoted.recipient@pusan.ac.kr", UserRole.USER);
        var captured = new kr.ac.pusan.pickle.security.AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), UserRole.USER, java.util.Map.of());
        user.setRole(UserRole.SYS_ADMIN);
        userRepository.saveAndFlush(user);
        for (boolean support : java.util.List.of(false, true)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> recipientCancellations.cancel(captured,
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    new kr.ac.pusan.pickle.request.dto.CancelRequestRecipientRequest("현재 MFA 확인", new kr.ac.pusan.pickle.workspace.dto.TerminationConfirmation("unused", true)), support, "127.0.0.1"))
                    .isInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class).hasMessageContaining("MFA_ENROLLMENT_REQUIRED");
        }
    }

    @Test
    void aPromotionWhileHttpCancellationWaitsForTheAdministrationLockRetainsMfaEnforcement() throws Exception {
        for (boolean support : java.util.List.of(false, true)) {
            User actor = createUser("mfa.waiting.recipient." + support + "@pusan.ac.kr", support ? UserRole.ORG_MANAGER : UserRole.USER);
            long orgId = kr.ac.pusan.pickle.support.SeedFixtures.seedOrgId(jdbc);
            if (support) kr.ac.pusan.pickle.support.SeedFixtures.grantOrgRole(jdbc, actor.getId(), orgId, UserRole.ORG_MANAGER);
            long workspaceId = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT','MFA 합성') returning id", Long.class);
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspaceId, actor.getId());
            long requestId = kr.ac.pusan.pickle.support.RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, actor.getId(), "MFA 합성", null);
            kr.ac.pusan.pickle.support.RequestFixtures.approveVmRequest(jdbc, requestId, actor.getId(), null, 1, 1024, 10);
            jdbc.update("update requests set status='APPROVED' where id=?", requestId);
            java.util.UUID recipientId = java.util.UUID.randomUUID();
            jdbc.update("insert into request_recipients(public_id,request_id,user_id,status) values(?,?,?,'QUEUED')", recipientId, requestId, actor.getId());
            String path = "/api/v1/" + (support ? "admin/" : "") + "requests/" + kr.ac.pusan.pickle.support.SeedFixtures.publicId(jdbc, "requests", requestId) + "/recipients/" + recipientId;
            String token = jwtService.createAccessToken(actor);
            String preview = mockMvc.perform(get(path + "/cancellation-impact").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String fingerprint = new tools.jackson.databind.ObjectMapper().readTree(preview).get("fingerprint").asString();
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> response = new java.util.concurrent.atomic.AtomicReference<>();
                tx.executeWithoutResult(transaction -> {
                    jdbc.queryForObject("select pg_advisory_xact_lock(1129074511,1)::text", String.class);
                    response.set(pool.submit(() -> {
                        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/cancel")
                                .header("Authorization", "Bearer " + token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"승격 중 현재 MFA 확인\",\"confirmation\":{\"confirmed\":true,\"fingerprint\":\"" + fingerprint + "\"}}"))
                                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
                        return null;
                    }));
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    while (jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=1129074511 and objid=1 and not granted", Long.class) == 0) {
                        if (System.nanoTime() > deadline) throw new AssertionError("HTTP cancellation did not reach the administration lock");
                        try { Thread.sleep(20); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }
                    jdbc.update("update users set role='SYS_ADMIN' where id=?", actor.getId());
                });
                response.get().get(15, java.util.concurrent.TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from request_recipients where public_id=?", String.class, recipientId)).isEqualTo("QUEUED");
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='request.recipient_cancel' and target_id=?", Long.class, recipientId.toString())).isZero();
        }
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

    /**
     * The org tier is asked for 2FA, not required to have it (operator
     * decision, 2026-08-25). Enforcement covers the two sys roles, which are
     * the ones holding every destructive operation.
     */
    @Test
    void enforcementCoversTheSysTierOnly() throws Exception {
        User sysManager = createUser("mfa.enforce.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER);
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(sysManager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

        for (UserRole orgTier : new UserRole[] {UserRole.ORG_ADMIN, UserRole.ORG_MANAGER}) {
            User orgAdmin = createUser("mfa.enforce." + orgTier.name().toLowerCase()
                    + "@pusan.ac.kr", orgTier);
            mockMvc.perform(get("/api/v1/workspaces")
                            .header("Authorization", "Bearer " + jwtService.createAccessToken(orgAdmin)))
                    .andExpect(status().isOk());
        }
    }
}
