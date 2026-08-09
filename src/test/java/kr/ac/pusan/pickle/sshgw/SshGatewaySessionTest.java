package kr.ac.pusan.pickle.sshgw;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated session audit endpoint
 * ({@code /internal/sshgw/session}): the <b>distinct-owner rule</b> over
 * the candidate fingerprint set — one owner ⇒ verified attribution + last_used_at
 * bump, two+ owners ⇒ null actor + {@code ambiguous} (framing prevention), zero
 * resolve / password ⇒ null actor — all fire-and-forget 204, never a 5xx.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class SshGatewaySessionTest {

    private static final String TOKEN = "test-sshgw-token";
    private static final String SSHGW_IP = "172.30.1.30";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String HOST_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyFixtureForSessionTestsAAAAAA";
    private static final String FP_MEMBER = "SHA256:sessionMemberFingerprintAAAAAAAAAAAAAAAAAAA";
    private static final String FP_MEMBER2 = "SHA256:sessionMemberSecondKeyBBBBBBBBBBBBBBBBBBB";
    private static final String FP_OTHER = "SHA256:sessionOtherOwnerFingerprintCCCCCCCCCCCCC";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;

    private long orgId;
    private long imageId;
    private long nodeId;
    private long poolId;
    private long workspaceId;
    private long memberId;
    private long otherId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'sshgw_route%'");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'guest-private'",
                Long.class);
        // Reuse a seeded node (do not create one) so this test does not add to the
        // org-headroom capacity other tests (e.g. ApprovalTest) assert against.
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        memberId = ensureUser("sshgw.session.member@pusan.ac.kr", "세션멤버");
        otherId = ensureUser("sshgw.session.other@pusan.ac.kr", "다른소유자");
        // Fresh keys each test so a prior test's last_used_at bump does not bleed in.
        jdbcTemplate.update("delete from user_ssh_keys where user_id in (?, ?)", memberId, otherId);
        workspaceId = createWorkspace();
        addMember(workspaceId, memberId, "MEMBER");
        registerKey(memberId, FP_MEMBER);
        registerKey(memberId, FP_MEMBER2);
        registerKey(otherId, FP_OTHER);
    }

    @Test
    void oneOwnerAcrossCandidatesGivesVerifiedAttributionAndBumpsAllKeys() throws Exception {
        String slug = uniqueSlug();
        long vmId = createVm(slug, "172.29.4.41");

        session(slug, CLIENT_IP, "publickey", List.of(FP_MEMBER, FP_MEMBER2))
                .andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(memberId);
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(vmId);
        assertThat(row.get("ip")).isEqualTo(CLIENT_IP);
        assertThat((String) row.get("detail")).contains(FP_MEMBER).contains(FP_MEMBER2)
                .contains("\"userId\"").doesNotContain("ambiguous");
        // both of that owner's candidate keys are bumped
        assertThat(lastUsed(FP_MEMBER)).isNotNull();
        assertThat(lastUsed(FP_MEMBER2)).isNotNull();
    }

    @Test
    void candidatesSpanningTwoOwnersAreAmbiguousNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.42");

        // a member offering a *fellow user's* key alongside their own must not pin
        // the session on either — the plugin can't prove which key signed
        session(slug, CLIENT_IP, "publickey", List.of(FP_MEMBER, FP_OTHER))
                .andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("detail")).contains("ambiguous")
                .contains(String.valueOf(memberId)).contains(String.valueOf(otherId));
        // framing prevention: no last_used_at bump on an ambiguous session
        assertThat(lastUsed(FP_MEMBER)).isNull();
        assertThat(lastUsed(FP_OTHER)).isNull();
    }

    @Test
    void zeroResolvingCandidatesIsBestEffortNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.43");

        session(slug, CLIENT_IP, "publickey",
                List.of("SHA256:vanishedKeyOneXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                        "SHA256:vanishedKeyTwoYYYYYYYYYYYYYYYYYYYYYYYYYYY"))
                .andExpect(status().isNoContent());
        assertThat(latestSession().get("actor_id")).isNull();
    }

    @Test
    void passwordSessionAuditsWithNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.44");

        session(slug, CLIENT_IP, "password", null).andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("detail")).contains("password").doesNotContain("fingerprint");
    }

    @Test
    void passwordSessionIgnoresAccumulatedCandidateKey() throws Exception {
        // password-fallback framing: an attacker offers the *victim's* public key
        // (a single candidate that would resolve to one owner) but fails to sign and
        // authenticates by the VM password on an opt-in VM. authMethod=password wins,
        // so the candidate is ignored and the session stays keyless (actor=null).
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.45");

        session(slug, CLIENT_IP, "password", List.of(FP_MEMBER))
                .andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("detail")).doesNotContain("userId").doesNotContain(FP_MEMBER);
        // the victim's key is not bumped
        assertThat(lastUsed(FP_MEMBER)).isNull();
    }

    @Test
    void unknownSlugStillRecordsTheVerifiedUser() throws Exception {
        // slug→vm misses (VM gone), but a single-owner candidate set still attributes
        session("no-such-vm-" + UUID.randomUUID(), CLIENT_IP, "publickey", List.of(FP_MEMBER))
                .andExpect(status().isNoContent());
        Map<String, Object> row = latestSession();
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(memberId);
        assertThat(row.get("target_id")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ResultActions session(String slug, String sourceIp, String authMethod,
            List<String> candidateFingerprints) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("slug", slug);
        body.put("sourceIp", sourceIp);
        body.put("authMethod", authMethod);
        if (candidateFingerprints != null) {
            body.put("candidateFingerprints", candidateFingerprints);
        }
        body.put("connectionId", "conn-" + UUID.randomUUID());
        return mockMvc.perform(post("/internal/sshgw/session")
                .with(request -> {
                    request.setRemoteAddr(SSHGW_IP);
                    return request;
                })
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> latestSession() {
        return jdbcTemplate.queryForMap("""
                select actor_id, target_id, ip, detail::text as detail from audit_logs
                 where action = 'sshgw.session' order by id desc limit 1
                """);
    }

    private Instant lastUsed(String fingerprint) {
        return jdbcTemplate.queryForObject(
                "select last_used_at from user_ssh_keys where fingerprint_sha256 = ?",
                Instant.class, fingerprint);
    }

    private void registerKey(long userId, String fingerprint) {
        jdbcTemplate.update("""
                insert into user_ssh_keys (user_id, name, algorithm, public_key, fingerprint_sha256)
                values (?, 'session-test', 'ssh-ed25519', 'ssh-ed25519 AAAA', ?)
                on conflict (fingerprint_sha256) do nothing
                """, userId, fingerprint);
    }

    private long ensureUser(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User(email, "{test-no-login}", name);
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerifiedAt(Instant.now());
            return userRepository.save(u);
        });
        return user.getId();
    }

    private long createWorkspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, '세션팀') returning id
                """, Long.class);
    }

    private void addMember(long workspaceId, long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    private long createVm(String slug, String ip) {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, memberId, "SSH 세션 테스트", imageId, 1, 1024, 10);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, status) values (?, ?::inet, 'ALLOCATED')
                returning id
                """, Long.class, poolId, ip);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status,
                                 ip_allocation_id, ssh_host_key)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status, ?, ?)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, slug, slug, imageId,
                VmStatus.RUNNING.name(), allocationId, HOST_KEY);
        jdbcTemplate.update("update ip_allocations set vm_id = ? where id = ?", vmId, allocationId);
        return vmId;
    }

    private static String uniqueSlug() {
        return "team-sess-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
