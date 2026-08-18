package kr.ac.pusan.pickle.sshgw;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToOwningWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal SSH-gateway route endpoint, v2:
 * the {@code /internal/**} filter chain (bearer + source-IP allowlist + global
 * backstop), the per-client rate limit, and the normative v2 chain — kill
 * switch &gt; slug &gt; RUNNING &gt; per-VM block &gt; identity (publickey
 * fingerprint→key→member / password opt-in) &gt; host-key pin &gt; live IP —
 * with per-user audit attribution on the publickey path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InternalSshGatewayRouteTest {

    private static final String TOKEN = "test-sshgw-token";
    private static final String SSHGW_IP = "172.30.1.30";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String OTHER_CLIENT_IP = "198.51.100.9";
    private static final int RATE_LIMIT = 5;
    private static final int GLOBAL_RATE_LIMIT = 12;

    private static final String HOST_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyFixtureForRouteResolutionTestsAAAA";
    private static final String FP_MEMBER = "SHA256:memberFingerprintForRouteTestsAAAAAAAAAAA";
    private static final String FP_STRANGER = "SHA256:strangerFingerprintForRouteTestsBBBBBBB";

    /** Small rate-limit budgets so both limiters can be tripped in short loops. */
    @DynamicPropertySource
    static void sshgwProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.sshgw.rate-limit-per-minute", () -> RATE_LIMIT);
        registry.add("pickle.sshgw.global-rate-limit-per-minute", () -> GLOBAL_RATE_LIMIT);
    }

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
    private long workspaceId;
    private long poolId;
    private long memberId;
    private long strangerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'sshgw_route%'");
        setGatewayEnabled(true);

        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'guest-private'",
                Long.class);
        nodeId = ensureNode();
        memberId = ensureUser("sshgw.member@pusan.ac.kr", "라우트멤버");
        strangerId = ensureUser("sshgw.stranger@pusan.ac.kr", "비구성원");
        workspaceId = createWorkspace();
        addMember(workspaceId, memberId, "MEMBER");
    }

    @Test
    void publickeyMemberResolvesWithHostKeysAndIsNotAudited() throws Exception {
        String slug = uniqueSlug();
        long vmId = createVm(slug, VmStatus.RUNNING, "172.29.4.11", false, HOST_KEY);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ip").value("172.29.4.11"))
                .andExpect(jsonPath("$.port").value(22))
                .andExpect(jsonPath("$.user").value("ubuntu"))
                .andExpect(jsonPath("$.hostKeys[0]").value(HOST_KEY));

        // gate-C: an allowed lookup runs on an unauthenticated offered key, so it
        // is NOT audited (the attributed record is the /session call) and it does
        // NOT bump last_used_at.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'sshgw.route' and target_id = ?",
                Long.class, pub("vms", vmId).toString())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select last_used_at from vm_ssh_keys where fingerprint_sha256 = ?",
                Instant.class, FP_MEMBER)).isNull();
    }

    @Test
    void allPinnedHostKeyTypesAreReturnedInHostKeys() throws Exception {
        String slug = uniqueSlug();
        // the VM presents multiple host-key types (ed25519/ecdsa/rsa), stored
        // newline-joined; the route must split them into the hostKeys array
        String multi = HOST_KEY
                + "\necdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItRouteEcdsaFixture"
                + "\nssh-rsa AAAAB3NzaC1yc2EAAAADAQABRouteRsaFixture";
        createVm(slug, VmStatus.RUNNING, "172.29.4.50", false, multi);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostKeys.length()").value(3))
                .andExpect(jsonPath("$.hostKeys[0]").value(HOST_KEY))
                .andExpect(jsonPath("$.hostKeys[1]")
                        .value("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItRouteEcdsaFixture"))
                .andExpect(jsonPath("$.hostKeys[2]")
                        .value("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABRouteRsaFixture"));
    }

    @Test
    void unknownFingerprintDeniedWithNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.12", false, HOST_KEY);

        publickey(slug, CLIENT_IP, SSHGW_IP, "SHA256:nobodyKnowsThisFingerprintXXXXXXXXXXXXX")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_UNKNOWN"));

        assertThat(latestDenied().get("actor_id")).isNull();
    }

    @Test
    void nonMemberKeyDeniedWithNullActorButKeyIdInDetail() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.13", false, HOST_KEY);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_STRANGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_NOT_MEMBER"));

        // gate-C: even after fingerprint identification a denial has a null actor
        // (no one can stamp "victim denied at VM X" via the victim's public key),
        // but the offered key still appears in detail for operators.
        Map<String, Object> row = latestDenied();
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("detail")).contains(FP_STRANGER);
    }

    /**
     * A key issued for another VM must be refused exactly like a key nobody
     * holds. Answering differently would turn this endpoint into a cross-VM
     * probe: offer a stolen public key at every slug and the one that answers
     * differently names the VM the key belongs to.
     */
    @Test
    void keyIssuedForAnotherVmIsRefusedAsUnknown() throws Exception {
        String otherSlug = uniqueSlug();
        long otherVmId = createVm(otherSlug, VmStatus.RUNNING, "172.29.4.70", false, HOST_KEY);
        String otherVmKey = "SHA256:keyThatBelongsToAnotherVmForRouteTestsDD";
        // One key per person per VM, so the standing fixture key has to give up
        // its slot before this VM can hold a differently-named one.
        jdbcTemplate.update("delete from vm_ssh_keys where vm_id = ? and user_id = ?",
                otherVmId, memberId);
        registerKey(otherVmId, memberId, otherVmKey);

        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.71", false, HOST_KEY);

        // Same owner, same workspace, MEMBER on both — only the VM differs.
        publickey(slug, CLIENT_IP, SSHGW_IP, otherVmKey)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_UNKNOWN"));

        Map<String, Object> row = latestDenied();
        assertThat(row.get("actor_id")).isNull();

        // ...and it still works where it was issued.
        publickey(otherSlug, OTHER_CLIENT_IP, SSHGW_IP, otherVmKey).andExpect(status().isOk());
    }

    /**
     * The VM-membership check compares two boxed ids, and a boxed comparison
     * that happens to work is the kind that works only for small numbers: Long
     * caches -128..127 and hands back the same instance, so a reference
     * comparison succeeds there and nowhere else. A fresh test database starts
     * its ids at 1, which is exactly the range that hides the fault, so this
     * pushes the VM sequence past the cache first.
     *
     * <p>The load-bearing half is the <b>accept</b>: a reference comparison
     * above the cache refuses a key at the very VM it was issued for, which
     * reads as "unknown key" and locks every member out. The refusal at the
     * other VM is asserted alongside so the case cannot pass by denying
     * everything.</p>
     */
    @Test
    void vmMembershipCheckHoldsForVmIdsAboveTheBoxedCache() throws Exception {
        // Idempotent: never rewinds the sequence behind rows this class already
        // inserted, so it cannot collide with an earlier case's VM.
        jdbcTemplate.queryForObject("""
                select setval(pg_get_serial_sequence('vms', 'id'),
                              greatest((select coalesce(max(id), 0) from vms), 5000))
                """, Long.class);

        String slug = uniqueSlug();
        long vmId = createVm(slug, VmStatus.RUNNING, "172.29.4.80", false, HOST_KEY);
        assertThat(vmId).isGreaterThan(127L);

        String otherSlug = uniqueSlug();
        long otherVmId = createVm(otherSlug, VmStatus.RUNNING, "172.29.4.81", false, HOST_KEY);
        assertThat(otherVmId).isGreaterThan(127L);

        // createVm re-points FP_MEMBER at the VM it just made, so the key under
        // test is the one issued for otherVmId — offered at both slugs.
        publickey(otherSlug, CLIENT_IP, SSHGW_IP, FP_MEMBER).andExpect(status().isOk());
        publickey(slug, OTHER_CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_UNKNOWN"));
    }

    @Test
    void suspendedOwnerDeniedAsUnknownKey() throws Exception {
        // A registered key whose owner is a MEMBER but whose account is no longer
        // ACTIVE must be denied like an unregistered key (least-leaky, no oracle).
        long ownerId = ensureUser("sshgw.suspended@pusan.ac.kr", "정지된소유자");
        addMember(workspaceId, ownerId, "MEMBER");
        String fingerprint = "SHA256:suspendedOwnerFingerprintForRouteTestsCCC";
        String slug = uniqueSlug();
        long vmId = createVm(slug, VmStatus.RUNNING, "172.29.4.60", false, HOST_KEY);
        registerKey(vmId, ownerId, fingerprint);

        // ACTIVE owner resolves normally
        setUserStatus(ownerId, UserStatus.ACTIVE);
        publickey(slug, CLIENT_IP, SSHGW_IP, fingerprint).andExpect(status().isOk());

        // DISABLED and WITHDRAWN owners are both denied as SSHGW_KEY_UNKNOWN
        setUserStatus(ownerId, UserStatus.DISABLED);
        publickey(slug, OTHER_CLIENT_IP, SSHGW_IP, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_UNKNOWN"));

        setUserStatus(ownerId, UserStatus.WITHDRAWN);
        publickey(slug, "203.0.113.44", SSHGW_IP, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_KEY_UNKNOWN"));
    }

    @Test
    void noHostKeyDeniedWithNullActor() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.14", false, null);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_NO_HOST_KEY"));

        assertThat(latestDenied().get("actor_id")).isNull();
    }

    @Test
    void passwordPathDefaultDenyThenOptIn() throws Exception {
        String slug = uniqueSlug();
        long vmId = createVm(slug, VmStatus.RUNNING, "172.29.4.15", false, HOST_KEY);

        // default-deny: ssh_password_enabled off → denied, actor null
        password(slug, CLIENT_IP, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_PASSWORD_DISABLED"));
        assertThat(latestDenied().get("actor_id")).isNull();

        // opt in → route granted; gate-C: an allowed lookup is not audited
        enablePasswordSsh(vmId);
        password(slug, CLIENT_IP, SSHGW_IP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostKeys[0]").value(HOST_KEY));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'sshgw.route' and target_id = ?",
                Long.class, pub("vms", vmId).toString())).isZero();
    }

    @Test
    void killSwitchOutranksPasswordOptIn() throws Exception {
        String slug = uniqueSlug();
        long vmId = createVm(slug, VmStatus.RUNNING, "172.29.4.16", false, HOST_KEY);
        enablePasswordSsh(vmId);
        setGatewayEnabled(false);

        // Even with the per-VM opt-in on, the global kill switch wins (precedence).
        password(slug, CLIENT_IP, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_GATEWAY_DISABLED"));
    }

    @Test
    void unknownSlugIsDeniedWith404() throws Exception {
        publickey("does-not-exist-" + UUID.randomUUID(), CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SSHGW_ROUTE_NOT_FOUND"));
    }

    @Test
    void stoppedVmIsDeniedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.STOPPED, "172.29.4.17", false, HOST_KEY);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_VM_NOT_RUNNING"));
    }

    @Test
    void blockedVmIsDeniedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.18", true, HOST_KEY);

        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_VM_BLOCKED"));
    }

    @Test
    void staleOrReclaimedAllocationIsDeniedWithNoAddress() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.30", false, HOST_KEY);
        long allocationId = jdbcTemplate.queryForObject(
                "select ip_allocation_id from vms where hostname = ?", Long.class, slug);
        jdbcTemplate.update("""
                update ip_allocations set status = 'RELEASED'::allocation_status, released_at = now()
                 where id = ?
                """, allocationId);
        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SSHGW_ROUTE_NO_ADDRESS"));
    }

    @Test
    void wrongTokenIsRejectedWith401() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.19", false, HOST_KEY);
        mockMvc.perform(routeRequest(slug, CLIENT_IP, SSHGW_IP, publickeyBody(slug, CLIENT_IP, FP_MEMBER))
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void wrongSourceIpIsRejectedWith403() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.20", false, HOST_KEY);
        publickey(slug, CLIENT_IP, "172.30.1.99", FP_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void perClientRateLimitDoesNotLockOutOtherClients() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.21", false, HOST_KEY);

        for (int i = 0; i < RATE_LIMIT; i++) {
            publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER).andExpect(status().isOk());
        }
        publickey(slug, CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        // a different client keeps working — no global lockout
        publickey(slug, OTHER_CLIENT_IP, SSHGW_IP, FP_MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ip").value("172.29.4.21"));
    }

    @Test
    void globalBackstopStillBoundsAFloodAcrossManySourceIps() throws Exception {
        String slug = uniqueSlug();
        createVm(slug, VmStatus.RUNNING, "172.29.4.22", false, HOST_KEY);
        for (int i = 0; i < GLOBAL_RATE_LIMIT; i++) {
            publickey(slug, "203.0.113." + (100 + i), SSHGW_IP, FP_MEMBER).andExpect(status().isOk());
        }
        publickey(slug, "203.0.113.250", SSHGW_IP, FP_MEMBER)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ResultActions publickey(String slug, String sourceIp, String peerIp, String fingerprint)
            throws Exception {
        return mockMvc.perform(routeRequest(slug, sourceIp, peerIp,
                publickeyBody(slug, sourceIp, fingerprint)).header("Authorization", "Bearer " + TOKEN));
    }

    private ResultActions password(String slug, String sourceIp, String peerIp) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("slug", slug);
        body.put("sourceIp", sourceIp);
        body.put("authMethod", "password");
        return mockMvc.perform(routeRequest(slug, sourceIp, peerIp, body)
                .header("Authorization", "Bearer " + TOKEN));
    }

    private Map<String, Object> publickeyBody(String slug, String sourceIp, String fingerprint) {
        Map<String, Object> body = new HashMap<>();
        body.put("slug", slug);
        body.put("sourceIp", sourceIp);
        body.put("authMethod", "publickey");
        body.put("publicKeyFingerprint", fingerprint);
        return body;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder routeRequest(
            String slug, String sourceIp, String peerIp, Map<String, Object> body) throws Exception {
        return post("/internal/sshgw/route")
                .with(request -> {
                    request.setRemoteAddr(peerIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private Map<String, Object> latestDenied() {
        return jdbcTemplate.queryForMap("""
                select actor_id, ip, detail::text as detail from audit_logs
                 where action = 'sshgw.route_denied' order by id desc limit 1
                """);
    }

    private void setGatewayEnabled(boolean enabled) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'ssh_gateway_enabled'",
                String.valueOf(enabled));
    }

    private void enablePasswordSsh(long vmId) {
        jdbcTemplate.update("""
                insert into vm_settings (vm_id, key, value, updated_at)
                values (?, 'ssh_password_enabled', 'true'::jsonb, now())
                """, vmId);
    }

    private void setUserStatus(long userId, UserStatus status) {
        jdbcTemplate.update("update users set status = ?::user_status where id = ?",
                status.name(), userId);
    }

    /**
     * Issues a key for one VM. The ciphertext is a placeholder: nothing on the
     * route path decrypts a private key, and the column is NOT NULL now that
     * every key here is platform-issued.
     *
     * <p>Fingerprints are globally unique, so a fixture fingerprint can name only
     * one VM at a time — the upsert re-points it at the VM under test rather than
     * failing on the second case that uses it.</p>
     */
    private void registerKey(long vmId, long userId, String fingerprint) {
        jdbcTemplate.update("""
                insert into vm_ssh_keys (vm_id, user_id, public_key, fingerprint_sha256,
                                         private_key_enc)
                values (?, ?, 'ssh-ed25519 AAAA', ?, 'v1:placeholder:placeholder')
                on conflict (fingerprint_sha256)
                    do update set vm_id = excluded.vm_id, user_id = excluded.user_id
                """, vmId, userId, fingerprint);
    }

    private long ensureNode() {
        Long existing = jdbcTemplate.query("select id from nodes where name = 'sshgw-route-test'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage, ip_pool_id)
                values ('sshgw-route-test', 'https://127.0.0.1:8006', 8, 16384, 'vmbr2', 'local-lvm', ?)
                returning id
                """, Long.class, poolId);
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
                values ('TEAM'::workspace_kind, '라우트팀') returning id
                """, Long.class);
    }

    private void addMember(long workspaceId, long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    private long createVm(String slug, VmStatus status, String ip, boolean blocked, String hostKey) {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, memberId, "SSH 라우트 테스트", imageId, 1, 1024, 10);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, status) values (?, ?::inet, 'ALLOCATED')
                returning id
                """, Long.class, poolId, ip);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status,
                                 ip_allocation_id, ssh_gateway_blocked, ssh_host_key)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status, ?, ?, ?)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, slug, slug, imageId,
                status.name(), allocationId, blocked, hostKey);
        jdbcTemplate.update("update ip_allocations set vm_id = ? where id = ?", vmId, allocationId);
        // SSH is never implied by workspace standing, so the VM is opened to the whole
        // owning workspace at MEMBER: every member's key resolves, an outsider's does
        // not — the same split the identity gate used to read off the workspace rung.
        grantVmToOwningWorkspace(jdbcTemplate, vmId, "MEMBER");
        // Keys belong to a (user, VM) pair now, so the standing fixtures are
        // issued here rather than once in setUp.
        registerKey(vmId, memberId, FP_MEMBER);
        registerKey(vmId, strangerId, FP_STRANGER);
        return vmId;
    }

    private static String uniqueSlug() {
        return "team-route-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
