package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Reading captured bodies: who may, who may not, and that one key's records
 * can never be reached through another key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmKeyBodyReadTest {

    private static final String PROMPT = "[{\"role\":\"system\",\"content\":\"you are helpful\"},"
            + "{\"role\":\"user\",\"content\":\"\ud559\uc0dd \uba85\ub2e8 \uc9c0\uc6cc\uc918\"}]";
    private static final String ANSWER = "\uc9c0\uc6b8 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-body-keyring.write-key-id", () -> "v1");
        registry.add("pickle.llm-body-keyring.read-keys", () -> "v1=" + Base64.getEncoder()
                .encodeToString(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

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
    private LlmBodyCipher cipher;

    private User keyOwner;
    private User bystander;
    private User outsider;
    private User wsOwner;
    private String keyOwnerToken;
    private String bystanderToken;
    private String outsiderToken;
    private String wsOwnerToken;
    private long orgId;
    private long workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("delete from llm_request_bodies");
        wsOwner = ensureUser("llmbody.wsowner@pusan.ac.kr", "\ubcf8\ubb38\uc6cc\ud06c\uc18c\uc720\uc790");
        keyOwner = ensureUser("llmbody.keyowner@pusan.ac.kr", "\ubcf8\ubb38\ud0a4\uc18c\uc720\uc790");
        bystander = ensureUser("llmbody.bystander@pusan.ac.kr", "\ubcf8\ubb38\uad6c\uacbd\uafbc");
        outsider = ensureUser("llmbody.outsider@pusan.ac.kr", "\ubcf8\ubb38\uc678\ubd80\uc778");
        wsOwnerToken = jwtService.createAccessToken(wsOwner);
        keyOwnerToken = jwtService.createAccessToken(keyOwner);
        bystanderToken = jwtService.createAccessToken(bystander);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        workspaceId = createTeam("\ubcf8\ubb38 \uae30\ub85d \uc870\ud68c " + UUID.randomUUID().toString().substring(0, 8));
        addMember(keyOwner.getEmail());
        addMember(bystander.getEmail());
    }

    @Test
    void aGrantHolderReadsTheListAndTheFullRecord() throws Exception {
        long keyId = issuedKey("\uae30\ub85d \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(bodyId.toString()))
                .andExpect(jsonPath("$.content[0].eventUuid").value("evt-1"))
                .andExpect(jsonPath("$.content[0].readable").value(true))
                .andExpect(jsonPath("$.content[0].requestTruncated").value(false));

        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + keyOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(keyOwner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                // The messages array comes back as an array, role by role.
                .andExpect(jsonPath("$.request[0].role").value("system"))
                .andExpect(jsonPath("$.request[1].content").value("\ud559\uc0dd \uba85\ub2e8 \uc9c0\uc6cc\uc918"))
                .andExpect(jsonPath("$.response").value(ANSWER));
    }

    @Test
    void oneKeysRecordCannotBeReachedThroughAnother() throws Exception {
        // The failure this guards is a detail query that finds the row by its
        // own id and then authorizes whatever key it belongs to. Both keys here
        // are readable by the same person, so only the path pairing separates
        // them -- which is exactly the condition under which that bug passes
        // every other test in this file.
        long mine = issuedKey("\ub0b4 \ud0a4");
        long theirs = issuedKey("\ub0a8\uc758 \ud0a4");
        UUID theirBody = insertBody(theirs, "evt-theirs", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(mine) + "/" + theirBody)
                        .header("Authorization", "Bearer " + keyOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(keyOwner)))
                .andExpect(status().isNotFound());

        // And the list never crosses either.
        mockMvc.perform(get(bodiesPath(mine))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aMemberWithoutAGrantIsRefusedAndANonMemberIsNotToldItExists() throws Exception {
        long keyId = issuedKey("\ubd80\uc5ec \uc5c6\uc74c \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + bystanderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + bystanderToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(bystander)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void owningTheWorkspaceDoesNotOpenTheContents() throws Exception {
        // Standing rights are a flag, not a rung. A workspace owner can delete
        // the key and manage its access list and still not read what was sent
        // through it.
        long keyId = issuedKey("\uc0c1\uc2dc\uad8c \ud0a4");
        insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + wsOwnerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void theFullRecordNeedsReauthAndTheListDoesNot() throws Exception {
        long keyId = issuedKey("\uc7ac\uc778\uc99d \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        // Browsing must not train a password prompt, so the list stays open.
        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk());
    }

    @Test
    void aTruncatedPromptComesBackAsAStringAndSaysSo() throws Exception {
        long keyId = issuedKey("\uc798\ub9bc \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-cut",
                "\"[{\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"\uae34 \ud504\ub86c\"", ANSWER, true, false);

        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + keyOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(keyOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestTruncated").value(true))
                .andExpect(jsonPath("$.responseTruncated").value(false))
                // A string, not an array: cutting JSON mid-way leaves nothing a
                // parser would take, so the prefix travels as a JSON string.
                .andExpect(jsonPath("$.request").isString());
    }

    @Test
    void turningRecordingOffLeavesWhatWasAlreadyStoredReadable() throws Exception {
        long keyId = issuedKey("\uaebc\uc9c4 \ud0a4");
        insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);
        jdbcTemplate.update("update llm_api_keys set record_bodies = false where id = ?", keyId);

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aRowWhoseKeyIsGoneDegradesInsteadOfFailing() throws Exception {
        // Retiring a keyring entry must not turn the list into a permanent 500
        // that hides the existence of the records as well as their contents.
        long keyId = issuedKey("\ubaa8\ub974\ub294 \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);
        jdbcTemplate.update(
                "update llm_request_bodies set cipher_key_id = 'retired' where public_id = ?",
                bodyId);

        mockMvc.perform(get(bodiesPath(keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].readable").value(false))
                .andExpect(jsonPath("$.content[0].requestPreview").doesNotExist());

        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + keyOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(keyOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false));
    }

    @Test
    void readingIsAudited() throws Exception {
        long keyId = issuedKey("\uac10\uc0ac \ud0a4");
        UUID bodyId = insertBody(keyId, "evt-1", PROMPT, ANSWER, false, false);

        mockMvc.perform(get(bodiesPath(keyId) + "/" + bodyId)
                        .header("Authorization", "Bearer " + keyOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(keyOwner)))
                .andExpect(status().isOk());

        // The target is the record, by its public id as a string -- what a
        // later reader has to be able to match against.
        assertThat(jdbcTemplate.queryForObject("""
                select target_id from audit_logs
                 where action = 'llm_key.body_read' order by id desc limit 1
                """, String.class)).isEqualTo(bodyId.toString());
    }

    // -- helpers ------------------------------------------------------------

    private String bodiesPath(long keyId) {
        return "/api/v1/llm-keys/" + pub("llm_api_keys", keyId) + "/bodies";
    }

    private String reauthFor(User user) {
        return ReauthTestSupport.seededReauthHeader(jdbcTemplate, user.getId());
    }

    private UUID insertBody(long keyId, String eventId, String requestJson, String response,
            boolean requestTruncated, boolean responseTruncated) {
        UUID keyPublicId = pub("llm_api_keys", keyId);
        UUID publicId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into llm_request_bodies (public_id, event_id, key_id, request_enc,
                        response_enc, request_truncated, response_truncated, request_bytes,
                        response_bytes, cipher_key_id, requested_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'v1', ?)
                """, publicId, eventId, keyId,
                cipher.encrypt(keyPublicId, eventId, LlmBodyCipher.Field.REQUEST, requestJson),
                cipher.encrypt(keyPublicId, eventId, LlmBodyCipher.Field.RESPONSE, response),
                requestTruncated, responseTruncated,
                requestJson.length(), response.length(),
                java.sql.Timestamp.from(Instant.now()));
        return publicId;
    }

    private long issuedKey(String name) {
        String hash = (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, left(?, 100))
                returning id
                """, Long.class, workspaceId, orgId, keyOwner.getId(), name, name);
        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, record_bodies, created_by)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', true, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, hash,
                "pickle-" + hash.substring(0, 6), keyOwner.getId());
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, user_id, role)
                values ('LLM_API_KEY', ?, 'USER', ?, 'OWNER'::resource_role)
                """, keyId, keyOwner.getId());
        return keyId;
    }

    private long createTeam(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + wsOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "workspaces",
                UUID.fromString(objectMapper.readTree(body).get("id").asString()));
    }

    private void addMember(String email) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + pub("workspaces", workspaceId) + "/members")
                        .header("Authorization", "Bearer " + wsOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauthFor(wsOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", "MEMBER"))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private UUID pub(String table, long id) {
        return jdbcTemplate.queryForObject(
                "select public_id from " + table + " where id = ?", UUID.class, id);
    }
}
