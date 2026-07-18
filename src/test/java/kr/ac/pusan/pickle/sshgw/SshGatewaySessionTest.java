package kr.ac.pusan.pickle.sshgw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmStatus;
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
 * Authenticated session audit endpoint (docs/api/internal.md Link 1
 * {@code /internal/sshgw/session}, gate-C): the publickey path writes a verified
 * per-user {@code sshgw.session} record and bumps last_used_at, the password
 * path records a null actor, and a resolution miss (deleted key / unknown slug)
 * is best-effort — always 204, never a 5xx that would tear down a live session.
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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;

    private long orgId;
    private long templateId;
    private long nodeId;
    private long poolId;
    private long groupId;
    private long memberId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'sshgw_route%'");
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'student-vmbr2'",
                Long.class);
        // Reuse a seeded node (do not create one) so this test does not add to the
        // org-headroom capacity other tests (e.g. ApprovalTest) assert against.
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        memberId = ensureUser("sshgw.session.member@pusan.ac.kr", "세션멤버");
        groupId = createGroup();
        addMember(groupId, memberId, "MEMBER");
        registerKey(memberId, FP_MEMBER);
    }

    @Test
    void publickeySessionAuditsVerifiedUserAndBumpsLastUsed() throws Exception {
        String slug = uniqueSlug();
        long vmId = createVm(slug, "172.29.4.41");

        session(slug, CLIENT_IP, "publickey", FP_MEMBER).andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(memberId);
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(vmId);
        assertThat(row.get("ip")).isEqualTo(CLIENT_IP);
        assertThat((String) row.get("detail")).contains(FP_MEMBER).contains(slug);
        assertThat(jdbcTemplate.queryForObject(
                "select last_used_at from user_ssh_keys where fingerprint_sha256 = ?",
                Instant.class, FP_MEMBER)).isNotNull();
    }

    @Test
    void passwordSessionAuditsWithNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.42");

        session(slug, CLIENT_IP, "password", null).andExpect(status().isNoContent());

        Map<String, Object> row = latestSession();
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("detail")).contains("password").doesNotContain("fingerprint");
    }

    @Test
    void deletedKeyRaceIsBestEffortNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, "172.29.4.43");

        // fingerprint no longer resolves (key deleted mid-connection) → still 204,
        // recorded with a null actor rather than 5xx
        session(slug, CLIENT_IP, "publickey", "SHA256:vanishedKeyFingerprintXXXXXXXXXXXXXXXXXXX")
                .andExpect(status().isNoContent());
        assertThat(latestSession().get("actor_id")).isNull();
    }

    @Test
    void unknownSlugStillRecordsTheVerifiedUser() throws Exception {
        // slug→vm misses (VM gone), but the verified fingerprint→user still holds
        session("no-such-vm-" + UUID.randomUUID(), CLIENT_IP, "publickey", FP_MEMBER)
                .andExpect(status().isNoContent());
        Map<String, Object> row = latestSession();
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(memberId);
        assertThat(row.get("target_id")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ResultActions session(String slug, String sourceIp, String authMethod,
            String fingerprint) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("slug", slug);
        body.put("sourceIp", sourceIp);
        body.put("authMethod", authMethod);
        if (fingerprint != null) {
            body.put("publicKeyFingerprint", fingerprint);
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

    private long createGroup() {
        return jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, '세션팀', ?) returning id
                """, Long.class, "sshgw-sess-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void addMember(long groupId, long userId, String role) {
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, ?::group_member_role)
                on conflict (group_id, user_id) do update set role = excluded.role
                """, groupId, userId, role);
    }

    private long createVm(String slug, String ip) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, 'SSH 세션 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, memberId, templateId);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, status) values (?, ?::inet, 'ALLOCATED')
                returning id
                """, Long.class, poolId, ip);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status,
                                 ip_allocation_id, ssh_host_key)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status, ?, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, slug, slug, templateId,
                VmStatus.RUNNING.name(), allocationId, HOST_KEY);
        jdbcTemplate.update("update ip_allocations set vm_id = ? where id = ?", vmId, allocationId);
        return vmId;
    }

    private static String uniqueSlug() {
        return "team-sess-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
