package kr.ac.pusan.pickle.sshkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-user SSH key surface (contract v0.8.0): registration parsing/409/422,
 * server generation + repeated private-key download (each audited), existence
 * masking of another user's key, the per-user cap, and deletion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class UserSshKeyTest {

    private static final String ED25519_PUB =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/cMyIC9ZMs5ssc "
                    + "fixture@pickle";
    private static final String ED25519_FP =
            "SHA256:/YMI/y63bR/1ageR+EQuaaCP72ObF73MApQtZH26tW0";
    private static final String ECDSA_PUB =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBP/TR8FpwtKe"
                    + "2qQyodrbWIUfOV+Tx47Qy1ctZFa/eMnEFVHj8Cl2DHf3a5Ydq9EEGkCTnpQFeXy5lcD6KWCLm0Y= x";

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
    private UserSshKeyRepository userSshKeyRepository;

    private User owner;
    private User other;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        owner = ensureUser("sshkey.owner@pusan.ac.kr", "키소유자");
        other = ensureUser("sshkey.other@pusan.ac.kr", "다른사용자");
        ownerToken = jwtService.createAccessToken(owner);
        otherToken = jwtService.createAccessToken(other);
        jdbcTemplate.update("delete from user_ssh_keys where user_id in (?, ?)",
                owner.getId(), other.getId());
    }

    @Test
    void registersPastedKeyThenLists() throws Exception {
        String body = registerKey(ownerToken, "연구실 노트북", ED25519_PUB)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.algorithm").value("ED25519"))
                .andExpect(jsonPath("$.fingerprint").value(ED25519_FP))
                .andExpect(jsonPath("$.privateKeyStored").value(false))
                .andExpect(jsonPath("$.publicKey").value(
                        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICxx5YF5Rp4GZP4rlNsvzVqTXiVyRF/"
                                + "cMyIC9ZMs5ssc"))
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();
        assertThat(keyId).isPositive();

        mockMvc.perform(get("/api/v1/me/ssh-keys").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fingerprint").value(ED25519_FP));

        // registration is audited without the key material (fact + fingerprint)
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "select detail::text as detail from audit_logs where action = 'user.ssh_key_add' "
                        + "and target_id = ?", keyId);
        assertThat(audits).hasSize(1);
        assertThat((String) audits.getFirst().get("detail")).contains(ED25519_FP);
    }

    @Test
    void duplicateFingerprintIsRejectedAcrossOwnersWithoutDisclosure() throws Exception {
        registerKey(ownerToken, "내 키", ED25519_PUB).andExpect(status().isCreated());
        // another user pasting the same key: 409, and the message must not reveal the owner
        registerKey(otherToken, "같은 키", ED25519_PUB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SSH_KEY_DUPLICATE"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("키소유자"))));
    }

    @Test
    void unsupportedKeyTypeIsUnprocessable() throws Exception {
        registerKey(ownerToken, "ecdsa", ECDSA_PUB)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("publicKey"));
    }

    @Test
    void perUserCapIsEnforced() throws Exception {
        for (int i = 0; i < UserSshKeyService.MAX_KEYS_PER_USER; i++) {
            jdbcTemplate.update("""
                    insert into user_ssh_keys (user_id, name, algorithm, public_key, fingerprint_sha256)
                    values (?, ?, 'ssh-ed25519', 'ssh-ed25519 AAAA', ?)
                    """, owner.getId(), "seed-" + i, "SHA256:seed-" + i);
        }
        registerKey(ownerToken, "열한번째", ED25519_PUB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SSH_KEY_LIMIT_EXCEEDED"));
    }

    @Test
    void generatedKeyFingerprintMatchesDownloadedPrivateKey(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) throws Exception {
        String body = mockMvc.perform(post("/api/v1/me/ssh-keys/generate")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "생성키 조회"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();
        String responseFp = objectMapper.readTree(body).get("fingerprint").asString();
        String responsePub = objectMapper.readTree(body).get("publicKey").asString();
        String dbFp = jdbcTemplate.queryForObject(
                "select fingerprint_sha256 from user_ssh_keys where id = ?", String.class, keyId);

        // (1) store == response, and the lookup the route uses finds it
        assertThat(dbFp).isEqualTo(responseFp);
        assertThat(userSshKeyRepository.findByFingerprintSha256(responseFp)).isPresent();

        // (2) the KEY the user actually uses = the downloaded private key. Its
        // public key (what sshpiperd fingerprints) must match what we stored.
        String pem = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/me/ssh-keys/" + keyId + "/private-key")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("privateKey").asString();
        org.junit.jupiter.api.Assumptions.assumeTrue(commandExists(), "ssh-keygen unavailable");
        java.nio.file.Path keyFile = dir.resolve("id_ed25519_pickle");
        java.nio.file.Files.writeString(keyFile, pem);
        java.nio.file.Files.setPosixFilePermissions(keyFile,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String rederived = run(dir, "ssh-keygen", "-y", "-f", keyFile.toString()).strip();
        String fpLine = run(dir, "ssh-keygen", "-lf", keyFile.toString());
        // the fingerprint of the downloaded key (what sshpiperd computes and looks
        // up) must equal the stored fingerprint — i.e. the generated key round-trips
        assertThat(fpLine).contains(responseFp);
        assertThat(rederived).startsWith(responsePub);
    }

    private static boolean commandExists() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start() != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String run(java.nio.file.Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    @Test
    void generatesKeyAndRedownloadsPrivateKeyEachAudited() throws Exception {
        String body = mockMvc.perform(post("/api/v1/me/ssh-keys/generate")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "피클에서 만든 키"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.algorithm").value("ED25519"))
                .andExpect(jsonPath("$.privateKeyStored").value(true))
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/me/ssh-keys/" + keyId + "/private-key")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.fileName").value("id_ed25519_pickle"))
                    .andExpect(jsonPath("$.privateKey").value(
                            org.hamcrest.Matchers.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")));
        }
        // every download audited; never the private key
        List<Map<String, Object>> downloads = jdbcTemplate.queryForList(
                "select detail::text as detail from audit_logs where action = 'user.ssh_key_download' "
                        + "and target_id = ?", keyId);
        assertThat(downloads).hasSize(2);
        assertThat((String) downloads.getFirst().get("detail")).doesNotContain("PRIVATE KEY");
    }

    @Test
    void pastedKeyHasNoDownloadablesPrivateKey() throws Exception {
        String body = registerKey(ownerToken, "붙여넣기", ED25519_PUB)
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();
        mockMvc.perform(get("/api/v1/me/ssh-keys/" + keyId + "/private-key")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void othersKeyIsMaskedAsNotFoundOnDownloadAndDelete() throws Exception {
        String body = registerKey(ownerToken, "내 키", ED25519_PUB)
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(get("/api/v1/me/ssh-keys/" + keyId + "/private-key")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/me/ssh-keys/" + keyId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesOwnKey() throws Exception {
        String body = registerKey(ownerToken, "삭제할 키", ED25519_PUB)
                .andReturn().getResponse().getContentAsString();
        long keyId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/v1/me/ssh-keys/" + keyId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/ssh-keys").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'user.ssh_key_delete' and target_id = ?",
                Long.class, keyId)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions registerKey(String token,
            String name, String publicKey) throws Exception {
        return mockMvc.perform(post("/api/v1/me/ssh-keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name, "publicKey", publicKey))));
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
