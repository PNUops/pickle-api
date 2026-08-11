package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.hamcrest.Matchers;
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
 * The LLM API key read surface: the list, the detail, and what each standing
 * is allowed to learn from them.
 *
 * <p>The sharp assertions are the negative ones. A key's row must never carry
 * the token hash — not to anyone, in any field — and its prefix only to a
 * grant holder; a non-member must not be able to tell an existing key from a
 * missing one. The positive cases exist mostly so the negatives are proven
 * against responses that demonstrably do carry data when allowed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmKeyQueryTest {

    /**
     * What the fixture key's plaintext would hash to — must appear nowhere.
     * Fresh per key: the stored hash is globally unique by index, exactly
     * because two keys must never share a secret.
     */
    private String tokenHash;
    /** The visible prefix — shown to grant holders, absent from restricted rows. */
    private String tokenPrefix;

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

    private User wsOwner;
    private User keyOwner;
    private User bystander;
    private User outsider;
    private String wsOwnerToken;
    private String keyOwnerToken;
    private String bystanderToken;
    private String outsiderToken;
    private long orgId;
    private long workspaceId;
    private String workspaceName;

    @BeforeEach
    void setUp() throws Exception {
        wsOwner = ensureUser("llmread.wsowner@pusan.ac.kr", "키워크스페이스소유자");
        keyOwner = ensureUser("llmread.keyowner@pusan.ac.kr", "키소유자");
        bystander = ensureUser("llmread.bystander@pusan.ac.kr", "키구경꾼");
        outsider = ensureUser("llmread.outsider@pusan.ac.kr", "키외부인");
        wsOwnerToken = jwtService.createAccessToken(wsOwner);
        keyOwnerToken = jwtService.createAccessToken(keyOwner);
        bystanderToken = jwtService.createAccessToken(bystander);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        String slug = "llmread-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceName = "LLM 키 조회 테스트 " + slug;
        workspaceId = createTeam(workspaceName);
        addMember(keyOwner.getEmail());
        addMember(bystander.getEmail());
    }

    @Test
    void aNonMemberCannotTellTheKeyFromAMissingOne() throws Exception {
        long keyId = createIssuedKey("외부인차단 키");

        // Absent from the list, whether filtered to the workspace or not — and
        // the workspace filter answers the same empty page as an unknown id.
        mockMvc.perform(get("/api/v1/llm-keys")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/llm-keys?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The detail masks existence: 404, exactly what an unknown id answers.
        mockMvc.perform(get("/api/v1/llm-keys/" + pub("llm_api_keys", keyId))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/llm-keys/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMemberWithoutAGrantGetsTheRestrictedRowAndNoTokenPrefix() throws Exception {
        long keyId = createIssuedKey("제한행 키");
        String row = "$.content[?(@.id=='" + pub("llm_api_keys", keyId) + "')]";

        String body = mockMvc.perform(get("/api/v1/llm-keys?workspaceId="
                        + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + bystanderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".accessLimited").value(Matchers.contains(true)))
                .andExpect(jsonPath(row + ".name").value(Matchers.contains("제한행 키")))
                .andExpect(jsonPath(row + ".status").value(Matchers.contains("ACTIVE")))
                .andExpect(jsonPath(row + ".ownerNames[0]")
                        .value(Matchers.contains(keyOwner.getName())))
                .andExpect(jsonPath(row + ".accessManageAllowed").value(Matchers.contains(false)))
                .andReturn().getResponse().getContentAsString();
        // Asserted against the whole body, not one field: nothing derived from
        // the secret, and nothing about the key's configuration, may appear in
        // a restricted row wherever it might be carried.
        assertThat(body).doesNotContain(tokenPrefix);
        assertThat(body).doesNotContain(tokenHash);
        assertThat(body).doesNotContain("제한행 키 용도");

        // The detail is an honest 403: the list already told them it exists.
        mockMvc.perform(get("/api/v1/llm-keys/" + pub("llm_api_keys", keyId))
                        .header("Authorization", "Bearer " + bystanderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGrantHolderSeesTheFullRowAndDetail() throws Exception {
        long keyId = createIssuedKey("공개행 키");
        String row = "$.content[?(@.id=='" + pub("llm_api_keys", keyId) + "')]";

        mockMvc.perform(get("/api/v1/llm-keys?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".accessLimited").value(Matchers.contains(false)))
                .andExpect(jsonPath(row + ".tokenPrefix").value(Matchers.contains(tokenPrefix)))
                .andExpect(jsonPath(row + ".purpose").value(Matchers.contains("공개행 키 용도")))
                .andExpect(jsonPath(row + ".rpm").value(Matchers.contains(60)))
                .andExpect(jsonPath(row + ".workspaceName").value(Matchers.contains(workspaceName)));

        mockMvc.perform(get("/api/v1/llm-keys/" + pub("llm_api_keys", keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("공개행 키"))
                .andExpect(jsonPath("$.tokenPrefix").value(tokenPrefix))
                .andExpect(jsonPath("$.rpm").value(60))
                .andExpect(jsonPath("$.tpm").value(100000))
                .andExpect(jsonPath("$.concurrency").value(4))
                .andExpect(jsonPath("$.recordBodies").value(false))
                .andExpect(jsonPath("$.myResourceRole").value("OWNER"))
                .andExpect(jsonPath("$.accessManageAllowed").value(true))
                .andExpect(jsonPath("$.workspaceId").value(
                        pub("workspaces", workspaceId).toString()));
    }

    @Test
    void aWorkspaceOwnerWithoutAGrantGetsTheRestrictedRowWithTheManageFlag() throws Exception {
        long keyId = createIssuedKey("소유자복구 키");
        String row = "$.content[?(@.id=='" + pub("llm_api_keys", keyId) + "')]";

        // The way back in for a key whose own owner is gone: the row stays
        // restricted, but the console may offer the access list.
        String body = mockMvc.perform(get("/api/v1/llm-keys?workspaceId="
                        + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + wsOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".accessLimited").value(Matchers.contains(true)))
                .andExpect(jsonPath(row + ".accessManageAllowed").value(Matchers.contains(true)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(tokenPrefix);

        // Standing rights open the access list, not the key: the detail stays
        // an honest 403 until they put themselves on the list.
        mockMvc.perform(get("/api/v1/llm-keys/" + pub("llm_api_keys", keyId))
                        .header("Authorization", "Bearer " + wsOwnerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void theTokenHashAppearsInNoResponseSurface() throws Exception {
        long keyId = createIssuedKey("해시부재 키");

        // Every reader, both surfaces: the stored hash is what authenticates at
        // the gateway, and no standing — not even the key's own owner — is ever
        // handed it back.
        for (String token : new String[] {keyOwnerToken, bystanderToken, wsOwnerToken}) {
            String list = mockMvc.perform(get("/api/v1/llm-keys?workspaceId="
                            + pub("workspaces", workspaceId))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(list).doesNotContain(tokenHash);
        }
        String detail = mockMvc.perform(get("/api/v1/llm-keys/" + pub("llm_api_keys", keyId))
                        .header("Authorization", "Bearer " + keyOwnerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(tokenHash);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * An issued, active key with its secret's hash and prefix in place, owned
     * (in the access-list sense) by {@code keyOwner} alone — the shape approval
     * plus issue produces. Written straight to the tables because this test's
     * subject is downstream of the request flow.
     */
    private long createIssuedKey(String name) {
        tokenHash = (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
        tokenPrefix = "pickle-" + tokenHash.substring(0, 6);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, left(?, 100))
                returning id
                """, Long.class, workspaceId, orgId, keyOwner.getId(), name + " 용도", name);
        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          token_hash, token_prefix, status, rpm, tpm, concurrency,
                                          created_by)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 60, 100000, 4, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, name + " 용도", tokenHash,
                tokenPrefix, keyOwner.getId());
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
                        .header(ReauthTestSupport.HEADER, ReauthTestSupport.seededReauthFor(
                                jdbcTemplate, jwtService, wsOwnerToken))
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

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
