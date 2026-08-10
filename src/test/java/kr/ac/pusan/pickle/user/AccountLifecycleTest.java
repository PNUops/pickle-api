package kr.ac.pusan.pickle.user;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.workspace.PersonalWorkspaceService;
import kr.ac.pusan.pickle.mail.AsyncMailDispatcher;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.sshkey.UserSshKeyRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Password change / reset and account withdrawal end-to-end.
 * Sessions are minted via {@link JwtService} (not {@code /auth/login}) so this
 * suite never contends on the per-IP login rate limit; the few real logins
 * that verify a password actually changed carry a unique {@code
 * X-Forwarded-For} so they stay isolated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AccountLifecycleTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");
    private static final String OLD_PASSWORD = "Corr3ct-horse-battery!";
    private static final String NEW_PASSWORD = "N3w-donkey-cactus-lamp!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private UserSshKeyRepository userSshKeyRepository;
    @Autowired
    private PersonalWorkspaceService personalWorkspaceService;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private AsyncMailDispatcher mailDispatcher;

    @Test
    void passwordChangeSurvivesCurrentSessionAndKillsOthers() throws Exception {
        User user = createActiveUser("pw.change@pusan.ac.kr", "비번변경");
        String oldAccess = jwtService.createAccessToken(user);

        // wrong current password → 403 AUTH_PASSWORD_MISMATCH
        putJson("/api/v1/me/password", oldAccess,
                Map.of("currentPassword", "totally-wrong-1!", "newPassword", NEW_PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));

        // correct change → 200 with a fresh token pair
        MvcResult changed = putJson("/api/v1/me/password", oldAccess,
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("__Host-pickle_refresh"))
                .andExpect(cookie().exists("__Host-pickle_csrf"))
                .andReturn();
        String newAccess = accessToken(changed);

        // current session survives via the new token; the old token is dead
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        // old password no longer logs in; the new one does
        loginExpect(user.getEmail(), OLD_PASSWORD).andExpect(status().isUnauthorized());
        loginExpect(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'account.password_change' and target_id = ?",
                Long.class, user.getPublicId().toString())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void passwordResetIsUniformSingleUseAndKillsSessions() throws Exception {
        User user = createActiveUser("pw.reset@pusan.ac.kr", "비번재설정");
        String oldAccess = jwtService.createAccessToken(user);

        // request for a real and a non-existent account → identical 202, only the real one mails
        postPublic("/api/v1/auth/password-reset", Map.of("email", user.getEmail()))
                .andExpect(status().isAccepted());
        postPublic("/api/v1/auth/password-reset", Map.of("email", "nobody.here@pusan.ac.kr"))
                .andExpect(status().isAccepted());
        assertThat(mailDispatcher.awaitIdle(Duration.ofSeconds(10)))
                .as("mail dispatcher drained").isTrue();
        assertThat(mockMailSender.lastMessageTo("nobody.here@pusan.ac.kr")).isNull();

        MailMessage mail = mockMailSender.lastMessageTo(user.getEmail());
        assertThat(mail).isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.body());
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);

        // confirm → 200; the pre-reset session dies (token_version bumped)
        postPublic("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", NEW_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());

        // single-use: the same token again → 410 AUTH_RESET_TOKEN_EXPIRED
        postPublic("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", "Yet-4nother-pass-word!"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_RESET_TOKEN_EXPIRED"));

        // new password works, old one does not
        loginExpect(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
        loginExpect(user.getEmail(), OLD_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void withdrawBlockedForSoleOwnerOfWorkspaceWithActiveVms() throws Exception {
        User user = createActiveUser("wd.owner@pusan.ac.kr", "유일소유자");
        personalWorkspaceService.ensurePersonalWorkspace(user);
        Org org = ensureOrg();
        Workspace team = workspaceRepository.save(new Workspace(WorkspaceKind.TEAM, "연구팀", null));
        workspaceMemberRepository.save(new WorkspaceMember(team, user.getId(), WorkspaceMemberRole.OWNER));
        createActiveVm(team.getId(), org.getId(), user.getId());

        postJson("/api/v1/me/withdraw", jwtService.createAccessToken(user), Map.of("password", OLD_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE"));
    }

    @Test
    void withdrawBlockedWhenPersonalWorkspaceHasActiveVms() throws Exception {
        User user = createActiveUser("wd.personal@pusan.ac.kr", "개인VM보유");
        personalWorkspaceService.ensurePersonalWorkspace(user);
        Org org = ensureOrg();
        long personalWorkspaceId = personalWorkspaceId(user.getId());
        createActiveVm(personalWorkspaceId, org.getId(), user.getId());

        postJson("/api/v1/me/withdraw", jwtService.createAccessToken(user), Map.of("password", OLD_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_ACTIVE_VMS"));
    }

    @Test
    void withdrawTearsDownAccountAndBlocksReuse() throws Exception {
        User user = createActiveUser("wd.happy@pusan.ac.kr", "탈퇴자");
        personalWorkspaceService.ensurePersonalWorkspace(user);
        long personalWorkspaceId = personalWorkspaceId(user.getId());
        insertSshKey(user.getId());
        insertRefreshToken(user.getId());
        String access = jwtService.createAccessToken(user);

        // wrong password re-auth → 403
        postJson("/api/v1/me/withdraw", access, Map.of("password", "not-my-password-1!"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));

        MvcResult withdrawn = postJson("/api/v1/me/withdraw", access, Map.of("password", OLD_PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        List<String> cookies = withdrawn.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("__Host-pickle_refresh=").contains("Max-Age=0"));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(reloaded.getWithdrawnAt()).isNotNull();
        // login stays a uniform 401 for a WITHDRAWN account
        loginExpect(user.getEmail(), OLD_PASSWORD).andExpect(status().isUnauthorized());
        // memberships gone, PERSONAL workspace soft-deleted, SSH keys + sessions gone
        assertThat(workspaceMemberRepository.findWithWorkspaceByUserId(user.getId())).isEmpty();
        assertThat(workspaceRepository.findById(personalWorkspaceId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(userSshKeyRepository.countByUserId(user.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ?", Long.class, user.getId())).isZero();
        // status-change history records the self-withdrawal
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from user_status_changes
                 where user_id = ? and to_status = 'WITHDRAWN' and actor_id = ?
                """, Long.class, user.getId(), user.getId())).isEqualTo(1L);
        // the retained row still blocks re-registration, but the answer is the same
        // 202 every signup gets (consents present so nothing fails bean validation)
        postPublic("/api/v1/auth/signup",
                Map.of("email", user.getEmail(), "password", NEW_PASSWORD, "name", "재가입시도",
                        "consents", List.of(
                                Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                                Map.of("docType", "PRIVACY_POLICY", "version", 1))))
                .andExpect(status().isAccepted());
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = ?",
                Long.class, user.getEmail())).as("no account is created for a taken address")
                .isEqualTo(1L);
        User stillWithdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(stillWithdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private User createActiveUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(OLD_PASSWORD), name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private Org ensureOrg() {
        return orgRepository.findFirstByNameOrderByIdAsc("계정수명 테스트 기관")
                .orElseGet(() -> orgRepository.save(new Org("계정수명 테스트 기관", null)));
    }

    private long personalWorkspaceId(long userId) {
        return workspaceMemberRepository.findWithWorkspaceByUserId(userId).stream()
                .filter(m -> m.getWorkspace().getKind() == WorkspaceKind.PERSONAL)
                .map(m -> m.getWorkspace().getId())
                .findFirst().orElseThrow();
    }

    private void insertSshKey(long userId) {
        jdbcTemplate.update("""
                insert into user_ssh_keys (user_id, name, algorithm, public_key, fingerprint_sha256)
                values (?, 'wd-key', 'ssh-ed25519', 'ssh-ed25519 AAAA', ?)
                """, userId, "SHA256:" + UUID.randomUUID());
    }

    private void insertRefreshToken(long userId) {
        jdbcTemplate.update("""
                insert into refresh_tokens (user_id, token_hash, expires_at)
                values (?, ?, now() + interval '14 days')
                """, userId, "hash-" + UUID.randomUUID());
    }

    /** Minimal request→vm FK chain (RUNNING, so it counts as active). */
    private void createActiveVm(long workspaceId, long orgId, long requesterId) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "탈퇴 차단 테스트", imageId);
        String hostname = "acct-vm-" + UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'RUNNING'::vm_status)
                """, nodeId, workspaceId, orgId, requestId, hostname, hostname, imageId);
    }

    private static String uniqueSlug(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Real login isolated to a per-account forwarded IP (avoids the shared per-IP limit). */
    private ResultActions loginExpect(String email, String password) throws Exception {
        String ip = "10.0." + (Math.abs(email.hashCode()) % 250 + 1) + ".7";
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String accessToken(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private ResultActions postPublic(String uri, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(put(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
