package kr.ac.pusan.pickle.auth;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.ReauthInterceptor;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Sudo-mode reauthentication (contract v0.24.0): {@code POST /auth/reverify}
 * issue semantics (password proof, shared login lockout, sliding window, audit)
 * and the {@code X-Reauth-Token} gate in front of {@code @RequireReauth}
 * endpoints — multi-use within the 10-minute TTL, dead on expiry, on a
 * token_version bump, and for another user's token.
 * Distinct client IPs per test so the dual-key windows do not bleed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ReauthTest {

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(980_000);
    private static final String PASSWORD = "Corr3ct-horse-battery!";
    private static final String WRONG_PASSWORD = "totally-wrong-1!";
    private static final String VM_PASSWORD = "x7GmQ4vRk2LpWn9sCtYb8Zed";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CredentialCipher credentialCipher;

    private User owner;
    private User outsider;
    private String ownerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long imageId;
    private long workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        owner = ensureUser("reauth.owner@pusan.ac.kr", "재인증소유자");
        outsider = ensureUser("reauth.outsider@pusan.ac.kr", "재인증외부인");
        ownerToken = jwtService.createAccessToken(owner);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        workspaceId = createTeam("reauth-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void issuesTenMinuteTokenAndAuditsSuccess() throws Exception {
        Instant before = Instant.now();
        String body = reverify(ownerToken, PASSWORD, "10.98.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reauthToken").isNotEmpty())
                // bearer-equivalent secret in the body — same no-store the VM
                // password reveal and the SSH private-key download carry
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("reauthToken").asString();
        Instant expiresAt = Instant.parse(objectMapper.readTree(body).get("expiresAt").asString());
        assertThat(token).isNotBlank();
        assertThat(expiresAt).isAfter(before.plus(Duration.ofMinutes(9)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(11)));

        // stored hashed, pinned to the issuing token_version, never in the clear
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select token_hash, token_version from auth_reverifications
                 where token_hash = ?
                """, ReauthTestSupport.sha256Hex(token));
        assertThat(row.get("token_version")).isEqualTo(owner.getTokenVersion());
        assertThat((String) row.get("token_hash")).isNotEqualTo(token);

        assertThat(auditCount(owner.getId(), "success")).isPositive();
    }

    @Test
    void anonymousIssueIsUnauthorized() throws Exception {
        // reverify sits under the public /auth/** prefix but proves the CURRENT
        // user's password, so it must 401 before the body is even looked at
        mockMvc.perform(post("/api/v1/auth/reverify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        // an empty body must not turn the 401 into a validation 422 either
        mockMvc.perform(post("/api/v1/auth/reverify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsForbiddenAuditedAndEscalatesToLockout() throws Exception {
        User user = ensureUser("reauth.lockout@pusan.ac.kr", "재인증잠금");
        String access = jwtService.createAccessToken(user);
        long mismatchesBefore = auditCount(user.getId(), "mismatch");

        for (int i = 0; i < 5; i++) {
            reverify(access, WRONG_PASSWORD, "10.98.1.1")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        }
        assertThat(auditCount(user.getId(), "mismatch")).isEqualTo(mismatchesBefore + 5);
        // no token was minted by the failures
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_reverifications where user_id = ?", Long.class,
                user.getId())).isZero();

        // the shared login lockout now refuses even the correct password from the
        // address that did the guessing — this is the lockout, not the sliding
        // window, which reverify counts on its own scopes
        loginFrom(user.getEmail(), "10.98.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // and it stops there: the lockout is keyed on the (account, address) pair,
        // so the account's other clients are unaffected
        loginFrom(user.getEmail(), "10.98.1.2").andExpect(status().isOk());
    }

    private ResultActions loginFrom(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD))));
    }

    @Test
    void issueIsSlidingWindowLimited() throws Exception {
        User user = ensureUser("reauth.window@pusan.ac.kr", "재인증윈도우");
        String access = jwtService.createAccessToken(user);

        for (int i = 0; i < 10; i++) {
            reverify(access, PASSWORD, "10.98.2.1").andExpect(status().isOk());
        }
        reverify(access, PASSWORD, "10.98.2.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void protectedEndpointNeedsTheHeader() throws Exception {
        long vmId = createVm();

        // authenticated, authorized, but no sudo-mode proof → 403 REAUTH_REQUIRED
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("재인증이 필요합니다"));

        // a garbage header is no better than none
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, "not-a-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        // with a freshly issued token the request reaches the endpoint's own logic
        String reauth = ReauthTestSupport.reauthHeaderViaApi(mockMvc, objectMapper, ownerToken,
                PASSWORD);
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, reauth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value(VM_PASSWORD));
    }

    @Test
    void oneTokenCoversSeveralProtectedCallsWithinTheTtl() throws Exception {
        long vmId = createVm();
        String reauth = ReauthTestSupport.reauthHeaderViaApi(mockMvc, objectMapper, ownerToken,
                PASSWORD);

        // multi-use: one prompt covers a whole sensitive workflow
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, reauth))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/vms/" + vmId + "/settings")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, reauth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("settings", Map.of("ssh_password_enabled", true)))))
                .andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        long vmId = createVm();
        String rawToken = "reauth-expired-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into auth_reverifications (user_id, token_hash, token_version,
                                                  expires_at, created_ip)
                values (?, ?, ?, now() - interval '1 minute', '127.0.0.1')
                """, owner.getId(), ReauthTestSupport.sha256Hex(rawToken), owner.getTokenVersion());

        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, rawToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void passwordChangeInvalidatesOutstandingTokens() throws Exception {
        User user = ensureUser("reauth.bump@pusan.ac.kr", "재인증무효화");
        String access = jwtService.createAccessToken(user);
        String reauth = ReauthTestSupport.reauthHeaderViaApi(mockMvc, objectMapper, access,
                PASSWORD);
        // the token works before the bump (no membership needed: the gate runs
        // before the endpoint, so a masked 404 already proves it got through)
        long vmId = createVm();
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + access)
                        .header(ReauthInterceptor.REAUTH_HEADER, reauth))
                .andExpect(status().isNotFound());

        // changing the password bumps token_version — and the new access token
        // comes back in the response, so only the reauth token can fail here
        String changed = mockMvc.perform(put("/api/v1/me/password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", PASSWORD,
                                "newPassword", "An0ther-good-pw!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newAccess = objectMapper.readTree(changed).get("accessToken").asString();

        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + newAccess)
                        .header(ReauthInterceptor.REAUTH_HEADER, reauth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void anotherUsersTokenIsRejected() throws Exception {
        long vmId = createVm();
        String outsiderReauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate,
                outsider.getId());

        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER, outsiderReauth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        // and it is the OWNER's own token that unlocks the same call
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthInterceptor.REAUTH_HEADER,
                                ReauthTestSupport.seededReauthHeader(jdbcTemplate, owner.getId())))
                .andExpect(status().isOk());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ResultActions reverify(String access, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reverify")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private long auditCount(long actorId, String result) {
        return jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'auth.reverify' and actor_id = ? and detail->>'result' = ?
                """, Long.class, actorId, result);
    }

    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (workspace_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '재인증 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, workspaceId, orgId, owner.getId(), imageId);
        String hostname = "reauth-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 password_enc, password_hash)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status, ?, 'bcrypt-hash')
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet(), credentialCipher.encrypt(VM_PASSWORD));
        // Approval is what would name the requester owner of the VM; this one is
        // inserted directly, so the row is written here instead. Without it the
        // sudo-mode tests would be measuring authorization, not the reauth gate.
        grantVmToUser(jdbcTemplate, vmId, owner.getId(), "OWNER");
        return vmId;
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "재인증 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
