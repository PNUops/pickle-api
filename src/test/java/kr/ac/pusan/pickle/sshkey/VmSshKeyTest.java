package kr.ac.pusan.pickle.sshkey;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-VM SSH key surface (contract v0.42.0): issue once, re-download any number
 * of times with every download audited, re-issue as the user's own revocation,
 * delete, and the resource-scoped masking that decides who may do any of it.
 *
 * <p>The load-bearing case is {@link #issuedKeyRoundTripsThroughOpenssh()}: the
 * key the user actually presents is the downloaded private one, so its public
 * half has to be exactly what the gateway will look the fingerprint up by.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmSshKeyTest {

    /**
     * The opening of the OpenSSH envelope the issued PEM must carry. Stopping
     * before the words the commit hook scans for is deliberate: that hook is a
     * hard block with no allowance marker, and splitting the phrase up to slip
     * a full envelope past it would be the trick that makes the hook useless.
     */
    private static final String PEM_HEADER = "-----BEGIN OPENSSH";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private VmSshKeyRepository repository;

    private long orgId;
    private long imageId;
    private long nodeId;
    private long poolId;
    private long workspaceId;

    private User member;
    private User viewer;
    private User stranger;
    private String memberToken;
    private String viewerToken;
    private String strangerToken;
    private String memberReauth;
    private String viewerReauth;
    private String strangerReauth;

    private long vmId;
    private UUID vmPublicId;
    private String hostname;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'guest-private'",
                Long.class);
        nodeId = ensureNode();

        member = ensureUser("vmkey.member@pusan.ac.kr", "키멤버");
        viewer = ensureUser("vmkey.viewer@pusan.ac.kr", "열람자");
        stranger = ensureUser("vmkey.stranger@pusan.ac.kr", "외부인");
        memberToken = jwtService.createAccessToken(member);
        viewerToken = jwtService.createAccessToken(viewer);
        strangerToken = jwtService.createAccessToken(stranger);
        memberReauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate, member.getId());
        viewerReauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate, viewer.getId());
        strangerReauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate, stranger.getId());

        workspaceId = createWorkspace();
        addMember(member.getId(), "MEMBER");
        addMember(viewer.getId(), "MEMBER");

        vmId = createVm();
        vmPublicId = SeedFixtures.publicId(jdbcTemplate, "vms", vmId);
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "MEMBER");
        grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "VIEWER");
    }

    // ── issue / status ─────────────────────────────────────────────────────

    @Test
    void issuesOnceThenReportsTheKey() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").doesNotExist());

        String body = issue(memberToken, memberReauth)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fileName").value("pickle-" + hostname + ".pem"))
                .andExpect(jsonPath("$.privateKey").value(
                        org.hamcrest.Matchers.containsString(PEM_HEADER)))
                .andReturn().getResponse().getContentAsString();
        String fingerprint = objectMapper.readTree(body).get("key").get("fingerprint").asString();

        mockMvc.perform(get(base()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key.fingerprint").value(fingerprint))
                .andExpect(jsonPath("$.key.lastUsedAt").doesNotExist());

        // The issue audit names the VM and carries no key material.
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "select detail::text as detail from audit_logs "
                        + "where action = 'vm.ssh_key_issue' and target_id = ?",
                vmPublicId.toString());
        assertThat(audits).hasSize(1);
        String detail = String.valueOf(audits.getFirst().get("detail"));
        assertThat(detail).contains(fingerprint).doesNotContain("PRIVATE KEY");
    }

    @Test
    void issuingTwiceConflicts() throws Exception {
        issue(memberToken, memberReauth).andExpect(status().isCreated());
        issue(memberToken, memberReauth)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SSH_KEY_ALREADY_ISSUED"));
    }

    /**
     * The key is issued per person, not per VM: a second member of the same VM
     * gets their own pair rather than the first one's.
     */
    @Test
    void eachMemberGetsTheirOwnKeyForTheSameVm() throws Exception {
        grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "MEMBER");
        String first = fingerprintOf(issue(memberToken, memberReauth)
                .andExpect(status().isCreated()));
        String second = fingerprintOf(issue(viewerToken, viewerReauth)
                .andExpect(status().isCreated()));
        assertThat(first).isNotEqualTo(second);
        assertThat(repository.findByVmIdAndUserId(vmId, member.getId())).isPresent();
        assertThat(repository.findByVmIdAndUserId(vmId, viewer.getId())).isPresent();
    }

    // ── download / re-issue / delete ───────────────────────────────────────

    @Test
    void redownloadsAnyNumberOfTimesAndAuditsEach() throws Exception {
        issue(memberToken, memberReauth).andExpect(status().isCreated());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(base() + "/private-key")
                            .header("Authorization", "Bearer " + memberToken)
                            .header(ReauthTestSupport.HEADER, memberReauth))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.privateKey").value(
                            org.hamcrest.Matchers.containsString(PEM_HEADER)));
        }

        Integer downloads = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'vm.ssh_key_download' "
                        + "and target_id = ?", Integer.class, vmPublicId.toString());
        assertThat(downloads).isEqualTo(2);
    }

    @Test
    void reissueReplacesTheKeyAndRecordsWhatItRevoked() throws Exception {
        String before = fingerprintOf(issue(memberToken, memberReauth)
                .andExpect(status().isCreated()));

        String after = fingerprintOf(mockMvc.perform(post(base() + "/reissue")
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, memberReauth))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));

        assertThat(after).isNotEqualTo(before);
        // The old fingerprint stops resolving, which is what makes re-issue a
        // revocation rather than a convenience.
        assertThat(repository.findByFingerprintSha256(before)).isEmpty();
        assertThat(repository.findByFingerprintSha256(after)).isPresent();

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "select detail::text as detail from audit_logs "
                        + "where action = 'vm.ssh_key_reissue' and target_id = ?",
                vmPublicId.toString());
        assertThat(audits).hasSize(1);
        assertThat(String.valueOf(audits.getFirst().get("detail")))
                .contains(before).contains(after);
    }

    @Test
    void reissueAndDownloadBeforeIssuingAreNotFound() throws Exception {
        mockMvc.perform(post(base() + "/reissue")
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, memberReauth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(base() + "/private-key")
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, memberReauth))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAndCanIssueAgain() throws Exception {
        String first = fingerprintOf(issue(memberToken, memberReauth)
                .andExpect(status().isCreated()));

        mockMvc.perform(delete(base())
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, memberReauth))
                .andExpect(status().isNoContent());
        assertThat(repository.findByFingerprintSha256(first)).isEmpty();

        mockMvc.perform(get(base()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").doesNotExist());
        issue(memberToken, memberReauth).andExpect(status().isCreated());
    }

    @Test
    void destroyedVmRefusesANewKey() throws Exception {
        // The destroy pipeline deletes this VM's keys; letting a member mint a
        // fresh one afterwards would put the ciphertext straight back.
        jdbcTemplate.update("update vms set status = 'DELETED'::vm_status where id = ?", vmId);
        issue(memberToken, memberReauth)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        // Cleaning up what you already hold stays open.
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
    }

    // ── authorization ──────────────────────────────────────────────────────

    /** A non-member is not told the VM exists, on any of the five operations. */
    @Test
    void strangerGetsNotFoundEverywhere() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        issue(strangerToken, strangerReauth).andExpect(status().isNotFound());
        mockMvc.perform(post(base() + "/reissue")
                        .header("Authorization", "Bearer " + strangerToken)
                        .header(ReauthTestSupport.HEADER, strangerReauth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(base() + "/private-key")
                        .header("Authorization", "Bearer " + strangerToken)
                        .header(ReauthTestSupport.HEADER, strangerReauth))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(base())
                        .header("Authorization", "Bearer " + strangerToken)
                        .header(ReauthTestSupport.HEADER, strangerReauth))
                .andExpect(status().isNotFound());
    }

    /** A VIEWER can see the VM, so the refusal is an honest 403 rather than a mask. */
    @Test
    void viewerIsRefusedWithoutMasking() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ROLE_INSUFFICIENT"));
        issue(viewerToken, viewerReauth).andExpect(status().isForbidden());
    }

    /**
     * The ownership filter compares two boxed ids, and a boxed comparison that
     * happens to work is the kind that works only for small numbers: Long caches
     * -128..127 and hands back the same instance, so a reference comparison
     * succeeds there and nowhere else. A fresh test database starts its ids at 1,
     * which is exactly the range that hides the fault, so this pushes the user
     * sequence past the cache before granting anything.
     */
    @Test
    void ownerCheckHoldsForIdsAboveTheBoxedCache() throws Exception {
        jdbcTemplate.execute("alter sequence users_id_seq restart with 5000");
        User highId = userRepository.save(highIdUser());
        assertThat(highId.getId()).isGreaterThan(127L);
        String token = jwtService.createAccessToken(highId);
        String reauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate, highId.getId());
        try {
            addMember(highId.getId(), "MEMBER");
            grantVmToUser(jdbcTemplate, vmId, highId.getId(), "MEMBER");

            String fingerprint = fingerprintOf(issue(token, reauth)
                    .andExpect(status().isCreated()));
            // Reaching the stored key at all is the assertion: a failed ownership
            // comparison answers "no key issued" instead.
            mockMvc.perform(get(base() + "/private-key")
                            .header("Authorization", "Bearer " + token)
                            .header(ReauthTestSupport.HEADER, reauth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key.fingerprint").value(fingerprint));
        } finally {
            jdbcTemplate.update("delete from vm_ssh_keys where user_id = ?", highId.getId());
            jdbcTemplate.update("delete from resource_access_grants where user_id = ?",
                    highId.getId());
            jdbcTemplate.update("delete from workspace_members where user_id = ?", highId.getId());
            jdbcTemplate.update("delete from auth_reverifications where user_id = ?",
                    highId.getId());
            userRepository.deleteById(highId.getId());
        }
    }

    // ── the key itself ─────────────────────────────────────────────────────

    /**
     * What the user presents is the downloaded private key, so its public half
     * must be exactly the line whose fingerprint the gateway looks up.
     */
    @Test
    void issuedKeyRoundTripsThroughOpenssh(@TempDir Path dir) throws Exception {
        String body = issue(memberToken, memberReauth)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String fingerprint = objectMapper.readTree(body).get("key").get("fingerprint").asString();
        String pem = objectMapper.readTree(body).get("privateKey").asString();

        String stored = jdbcTemplate.queryForObject(
                "select fingerprint_sha256 from vm_ssh_keys where vm_id = ? and user_id = ?",
                String.class, vmId, member.getId());
        assertThat(stored).isEqualTo(fingerprint);
        assertThat(repository.findByFingerprintSha256(fingerprint)).isPresent();

        String storedPublic = jdbcTemplate.queryForObject(
                "select public_key from vm_ssh_keys where vm_id = ? and user_id = ?",
                String.class, vmId, member.getId());

        Assumptions.assumeTrue(commandExists(), "ssh-keygen unavailable");
        Path keyFile = dir.resolve("issued.pem");
        Files.writeString(keyFile, pem);
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        String rederived = run(dir, "ssh-keygen", "-y", "-f", keyFile.toString()).strip();
        String fpLine = run(dir, "ssh-keygen", "-lf", keyFile.toString());
        assertThat(fpLine).contains(fingerprint);
        assertThat(rederived).startsWith(storedPublic);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String base() {
        return "/api/v1/vms/" + vmPublicId + "/ssh-key";
    }

    private ResultActions issue(String token, String reauth) throws Exception {
        return mockMvc.perform(post(base())
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth));
    }

    private String fingerprintOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("key").get("fingerprint").asString();
    }

    private static boolean commandExists() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start() != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static String run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    private User highIdUser() {
        User user = new User("vmkey.highid@pusan.ac.kr", "{test-no-login}", "큰아이디");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return user;
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private long ensureNode() {
        Long existing = jdbcTemplate.query("select id from nodes where name = 'vmkey-test'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage, ip_pool_id)
                values ('vmkey-test', 'https://127.0.0.1:8006', 8, 16384, 'vmbr2', 'local-lvm', ?)
                returning id
                """, Long.class, poolId);
    }

    private long createWorkspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, 'VM 키 테스트 팀') returning id
                """, Long.class);
    }

    private void addMember(long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    /**
     * Inserted rather than provisioned, so the access list starts empty and each
     * case writes exactly the standing it means to test. RUNNING because nothing
     * here depends on state, and the key surface does not gate on it.
     */
    private long createVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                member.getId(), "VM 키 테스트", imageId, 1, 1024, 10);
        hostname = "vmkey-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId);
    }
}
