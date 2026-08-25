package kr.ac.pusan.pickle.security;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantLlmKeyToOwningWorkspace;
import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantLlmKeyToUser;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime enforcement of every resource-scoped LLM API key operation — the
 * key's row in the table-driven suite {@link VmAccessScopingTest} pioneered.
 *
 * <p>The matrix marks these ops {@code allow_resource_scoped:<ROLE>}, which the
 * annotation-reading {@link PermissionMatrixTest} cannot verify: the access
 * list is consulted in the service layer and there is no annotation to read.
 * So each op is driven over the real HTTP surface with a requester whose
 * standing is set precisely one rung at a time, and the answer asserted — 404
 * {@code RESOURCE_NOT_FOUND} for the outsider the key's existence is masked
 * from, 403 {@code WORKSPACE_ROLE_INSUFFICIENT} below the rung, neither denial
 * status at or above it.
 *
 * <p>Two of the key's ops sit at the OWNER rung for different reasons, and the
 * difference is the point rather than an accident:
 *
 * <ul>
 *   <li><b>issue is content access.</b> Minting the secret requires an OWNER
 *       <em>grant</em>; a workspace owner's standing rights must not satisfy
 *       it, and neither must an administrator role — nobody reads a key's
 *       plaintext through standing alone;</li>
 *   <li><b>revoke is a standing right.</b> A workspace owner with no grant may
 *       always take a key of their workspace away, as may an ORG_ADMIN of the
 *       owning org and a SYS_ADMIN — while an ORG_ADMIN of a different org
 *       still gets the masking 404.</li>
 * </ul>
 *
 * <p>The fixture key is deliberately left {@code PENDING} with a null
 * {@code token_hash} — the state between approval and the owner's first mint.
 * Issue on it succeeds (that is the mint), and revoke must reach it too: a key
 * nobody ever issued still represents a right that can be taken away.
 *
 * <p>"Allowed" is asserted as <em>neither 403 nor 404</em>, as in the VM
 * suite; every expected 403 additionally asserts the
 * {@code WORKSPACE_ROLE_INSUFFICIENT} code so a {@code REAUTH_REQUIRED} can
 * never be mistaken for a rung refusal, and the sudo-gated ops always carry a
 * live reauth token. Every (op, scenario) pair builds its own key: several of
 * these ops change what they touch, and a shared fixture would make the
 * outcome depend on execution order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmKeyAccessScopingTest {

    /**
     * The ops a workspace owner reaches through their standing rights rather
     * than through a rung: the access list, and taking the key away. Issue is
     * deliberately absent — minting the secret is content access, and standing
     * rights open nothing inside a resource.
     */
    private static final Set<String> MANAGED_OPS = Set.of("revokeLlmKey",
            "listLlmKeyAccessGrants", "addLlmKeyAccessGrant", "updateLlmKeyAccessGrant",
            "removeLlmKeyAccessGrant");

    /**
     * One resource-scoped operation as the permission matrix declares it.
     *
     * @param id       the contract operation id
     * @param method   HTTP method
     * @param path     path under {@code /api/v1}, with fixture placeholders
     * @param required the rung the matrix says this op needs
     * @param reauth   whether the endpoint is sudo-mode gated
     * @param body     request body template, or null for a bodiless request
     */
    private record ScopedOp(String id, HttpMethod method, String path, ResourceRole required,
            boolean reauth, String body) {
    }

    /** The five standings each op is driven with. */
    private enum Scenario {
        NON_MEMBER,
        MEMBER_WITHOUT_GRANT,
        GRANT_BELOW_RUNG,
        GRANT_AT_RUNG,
        WORKSPACE_OWNER_WITHOUT_GRANT
    }

    /** The throwaway key one (op, scenario) pair acts on, and a spare grant on it. */
    private record Fixture(long keyId, long grantId) {
    }

    /** Who is driving one case, and the reauth token they would need. */
    private record Requester(long userId, String token) {
    }

    /**
     * The eight resource-scoped LLM key operations, transcribed from
     * {@code permission-matrix.yaml}. Adding an op to the product means adding
     * one row here.
     */
    private static final List<ScopedOp> OPS = List.of(
            op("getLlmKey", HttpMethod.GET, "/llm-keys/{keyId}", ResourceRole.VIEWER),
            // Usage is content, not standing: what a key was used for sits on
            // the same side of the line as its detail, so a workspace owner
            // without a grant is refused here too.
            op("getLlmKeyUsage", HttpMethod.GET, "/llm-keys/{keyId}/usage", ResourceRole.VIEWER),

            bodyOp("updateLlmKey", HttpMethod.PATCH, "/llm-keys/{keyId}", ResourceRole.EDITOR,
                    "{\"name\":\"스코프\"}"),

            reauthOp("issueLlmKeyToken", HttpMethod.POST, "/llm-keys/{keyId}/token",
                    ResourceRole.OWNER, null),
            reauthOp("revokeLlmKey", HttpMethod.POST, "/llm-keys/{keyId}/revoke",
                    ResourceRole.OWNER, null),
            op("listLlmKeyAccessGrants", HttpMethod.GET, "/llm-keys/{keyId}/access",
                    ResourceRole.OWNER),
            reauthOp("addLlmKeyAccessGrant", HttpMethod.POST, "/llm-keys/{keyId}/access",
                    ResourceRole.OWNER,
                    "{\"granteeType\":\"USER\",\"userId\":\"{spareUserId}\",\"role\":\"VIEWER\"}"),
            reauthOp("updateLlmKeyAccessGrant", HttpMethod.PATCH,
                    "/llm-keys/{keyId}/access/{grantId}", ResourceRole.OWNER,
                    "{\"role\":\"VIEWER\"}"),
            reauthOp("removeLlmKeyAccessGrant", HttpMethod.DELETE,
                    "/llm-keys/{keyId}/access/{grantId}", ResourceRole.OWNER, null));

    private static ScopedOp op(String id, HttpMethod method, String path, ResourceRole required) {
        return new ScopedOp(id, method, path, required, false, null);
    }

    private static ScopedOp bodyOp(String id, HttpMethod method, String path,
            ResourceRole required, String body) {
        return new ScopedOp(id, method, path, required, false, body);
    }

    private static ScopedOp reauthOp(String id, HttpMethod method, String path,
            ResourceRole required, String body) {
        return new ScopedOp(id, method, path, required, true, body);
    }

    /** Every (op, scenario) pair; the below-rung case has no meaning at the floor. */
    private static Stream<Arguments> cases() {
        List<Arguments> arguments = new ArrayList<>();
        for (ScopedOp scopedOp : OPS) {
            for (Scenario scenario : Scenario.values()) {
                if (scenario == Scenario.GRANT_BELOW_RUNG
                        && scopedOp.required() == ResourceRole.VIEWER) {
                    continue;
                }
                arguments.add(Arguments.of(Named.of(scopedOp.id(), scopedOp), scenario));
            }
        }
        return arguments.stream();
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
    private OrgRepository orgRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User workspaceOwner;
    private User member;
    private User listedBystander;
    private User spareBystander;
    private User outsider;
    private User orgAdminSameOrg;
    private User orgAdminOtherOrg;
    private User sysAdmin;
    private String workspaceOwnerToken;
    private String memberToken;
    private String outsiderToken;
    private long orgId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        Org otherOrg = orgRepository.findFirstByNameOrderByIdAsc("키 범위 테스트 기관 B")
                .orElseGet(() -> orgRepository.save(new Org("키 범위 테스트 기관 B", null)));
        workspaceOwner = ensureUser("llmscope.owner@pusan.ac.kr", "키범위워크스페이스소유자",
                UserRole.USER, null);
        member = ensureUser("llmscope.member@pusan.ac.kr", "키범위구성원", UserRole.USER, null);
        listedBystander = ensureUser("llmscope.listed@pusan.ac.kr", "키범위등재자", UserRole.USER,
                null);
        spareBystander = ensureUser("llmscope.spare@pusan.ac.kr", "키범위예비자", UserRole.USER,
                null);
        outsider = ensureUser("llmscope.outsider@pusan.ac.kr", "키범위외부인", UserRole.USER, null);
        orgAdminSameOrg = ensureUser("llmscope.orgadmin.a@pusan.ac.kr", "키범위기관관리자",
                UserRole.ORG_ADMIN, orgId);
        orgAdminOtherOrg = ensureUser("llmscope.orgadmin.b@pusan.ac.kr", "키범위타기관관리자",
                UserRole.ORG_ADMIN, otherOrg.getId());
        sysAdmin = ensureUser("llmscope.sysadmin@pusan.ac.kr", "키범위시스템관리자",
                UserRole.SYS_ADMIN, null);
        workspaceOwnerToken = jwtService.createAccessToken(workspaceOwner);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
        workspaceId = ensureWorkspace();
        addMember(workspaceOwner.getId(), "OWNER");
        addMember(member.getId(), "MEMBER");
        addMember(listedBystander.getId(), "MEMBER");
        addMember(spareBystander.getId(), "MEMBER");
        // The outsider must stay out of the workspace: their whole purpose is the
        // 404 mask, which a stray membership row would turn into a 403. The admin
        // accounts stay out for the same reason — their reach, where they have
        // one, must come from their role and not from a membership.
        for (long userId : new long[] {outsider.getId(), orgAdminSameOrg.getId(),
                orgAdminOtherOrg.getId(), sysAdmin.getId()}) {
            jdbcTemplate.update(
                    "delete from workspace_members where workspace_id = ? and user_id = ?",
                    workspaceId, userId);
        }
    }

    @ParameterizedTest(name = "[{index}] {0} — {1}")
    @MethodSource("cases")
    void resourceScopedOpHonoursItsDeclaredRung(ScopedOp scopedOp, Scenario scenario)
            throws Exception {
        Fixture fixture = newFixture();
        Requester requester = standingFor(scopedOp, scenario, fixture);

        MockHttpServletResponse response = call(scopedOp, fixture, requester);
        String where = scopedOp.id() + " / " + scenario;

        if (scenario == Scenario.NON_MEMBER) {
            assertThat(response.getStatus()).as("%s: an outsider must not learn the key exists",
                    where).isEqualTo(404);
            assertThat(errorCode(response)).as("%s: 404 error code", where)
                    .isEqualTo("RESOURCE_NOT_FOUND");
        } else if (allowed(scopedOp, scenario)) {
            // Neither denial status: what comes back instead (200, 201, 204,
            // 409, …) belongs to the op's own state machine, not to authz.
            assertThat(response.getStatus())
                    .as("%s: authorization must pass at the declared rung (body: %s)", where,
                            body(response))
                    .isNotIn(403, 404);
            assertThat(response.getStatus()).as("%s: passed authz but failed unexpectedly (%s)",
                    where, body(response)).isLessThan(500);
        } else {
            assertThat(response.getStatus()).as("%s: below the declared rung must be refused",
                    where).isEqualTo(403);
            // Pins the reason: a sudo-mode or generic denial answers 403 too,
            // and would otherwise let this case pass without the rung being
            // consulted at all.
            assertThat(errorCode(response)).as("%s: 403 error code", where)
                    .isEqualTo("WORKSPACE_ROLE_INSUFFICIENT");
        }
    }

    /**
     * Revoking is a standing right past the workspace too: an ORG_ADMIN of the
     * owning org and a SYS_ADMIN may take a key away holding no grant and no
     * workspace membership, while an ORG_ADMIN of a different org is answered
     * with the same masking 404 as any outsider.
     */
    @Test
    void adminStandingRevokesWithoutAnyGrantButOnlyInsideItsOrg() throws Exception {
        long keyForOrgAdmin = insertKey();
        MockHttpServletResponse sameOrg = call(revokeOp(), new Fixture(keyForOrgAdmin, 0),
                requester(orgAdminSameOrg));
        assertThat(sameOrg.getStatus()).as("own-org ORG_ADMIN revokes with no grant (body: %s)",
                body(sameOrg)).isEqualTo(204);
        assertThat(status(keyForOrgAdmin)).isEqualTo("REVOKED");

        long keyForSysAdmin = insertKey();
        MockHttpServletResponse platform = call(revokeOp(), new Fixture(keyForSysAdmin, 0),
                requester(sysAdmin));
        assertThat(platform.getStatus()).as("SYS_ADMIN revokes with no grant (body: %s)",
                body(platform)).isEqualTo(204);
        assertThat(status(keyForSysAdmin)).isEqualTo("REVOKED");

        long keyKeptFromForeignAdmin = insertKey();
        MockHttpServletResponse foreign = call(revokeOp(),
                new Fixture(keyKeptFromForeignAdmin, 0), requester(orgAdminOtherOrg));
        assertThat(foreign.getStatus())
                .as("a foreign-org ORG_ADMIN must not learn the key exists").isEqualTo(404);
        assertThat(errorCode(foreign)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(status(keyKeptFromForeignAdmin)).isEqualTo("PENDING");
    }

    /**
     * The other half of the issue/revoke asymmetry: minting the secret is
     * content access, and no standing satisfies it. The workspace owner is
     * refused in the open; the administrators, not being members, are masked —
     * the admin override that lets them revoke deliberately stops there.
     */
    @Test
    void noStandingMintsASecretWithoutAnOwnerGrant() throws Exception {
        long keyId = insertKey();

        MockHttpServletResponse asWorkspaceOwner = call(issueOp(), new Fixture(keyId, 0),
                new Requester(workspaceOwner.getId(), workspaceOwnerToken));
        assertThat(asWorkspaceOwner.getStatus())
                .as("a workspace owner's standing rights must not mint a secret").isEqualTo(403);
        assertThat(errorCode(asWorkspaceOwner)).isEqualTo("WORKSPACE_ROLE_INSUFFICIENT");

        for (User admin : List.of(orgAdminSameOrg, sysAdmin)) {
            MockHttpServletResponse asAdmin = call(issueOp(), new Fixture(keyId, 0),
                    requester(admin));
            assertThat(asAdmin.getStatus())
                    .as("%s: an administrator holds no path to the plaintext", admin.getEmail())
                    .isEqualTo(404);
            assertThat(errorCode(asAdmin)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(status(keyId)).isEqualTo("PENDING");
        assertThat(tokenHash(keyId)).as("nothing was minted").isNull();
    }

    /**
     * Issuing again is rotation: the new plaintext hashes to what is stored,
     * and the old hash is gone — which is what makes "the old value stops
     * working" a property of the table rather than a promise.
     */
    @Test
    void rotationReplacesTheHashAndTheOldSecretDies() throws Exception {
        long keyId = insertKey();
        grantLlmKeyToUser(jdbcTemplate, keyId, member.getId(), "OWNER");

        MockHttpServletResponse first = call(issueOp(), new Fixture(keyId, 0), requester(member));
        assertThat(first.getStatus()).as("first mint (body: %s)", body(first)).isEqualTo(200);
        String firstToken = objectMapper.readTree(body(first)).get("token").asString();
        assertThat(tokenHash(keyId)).isEqualTo(ReauthTestSupport.sha256Hex(firstToken));
        assertThat(status(keyId)).isEqualTo("ACTIVE");

        MockHttpServletResponse second = call(issueOp(), new Fixture(keyId, 0), requester(member));
        assertThat(second.getStatus()).as("rotation (body: %s)", body(second)).isEqualTo(200);
        String secondToken = objectMapper.readTree(body(second)).get("token").asString();
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(tokenHash(keyId)).isEqualTo(ReauthTestSupport.sha256Hex(secondToken));
        // The old secret authenticates nothing anywhere: its hash is not merely
        // replaced on this row, it exists on no row at all.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_api_keys where token_hash = ?", Long.class,
                ReauthTestSupport.sha256Hex(firstToken))).isZero();
        assertThat(status(keyId)).isEqualTo("ACTIVE");
    }

    /** A revoked key is dead: issuing it again is refused as a conflict, not obeyed. */
    @Test
    void revokedKeyCannotBeIssuedAgain() throws Exception {
        long keyId = insertKey();
        grantLlmKeyToUser(jdbcTemplate, keyId, member.getId(), "OWNER");
        assertThat(call(revokeOp(), new Fixture(keyId, 0), requester(member)).getStatus())
                .isEqualTo(204);

        MockHttpServletResponse issued = call(issueOp(), new Fixture(keyId, 0), requester(member));
        assertThat(issued.getStatus()).as("issue after revoke (body: %s)", body(issued))
                .isEqualTo(409);
        assertThat(errorCode(issued)).isEqualTo("LLM_KEY_REVOKED");
        assertThat(status(keyId)).isEqualTo("REVOKED");
        assertThat(tokenHash(keyId)).as("no secret came into being").isNull();
    }

    /**
     * Revoke reaches a key that was never issued — {@code PENDING}, null hash —
     * and is idempotent: a retried click must not move the timestamp that says
     * when access actually ended.
     */
    @Test
    void revokeReachesAPendingKeyAndASecondCallMovesNothing() throws Exception {
        long keyId = insertKey();
        grantLlmKeyToUser(jdbcTemplate, keyId, member.getId(), "OWNER");
        assertThat(tokenHash(keyId)).as("the fixture key was never issued").isNull();

        assertThat(call(revokeOp(), new Fixture(keyId, 0), requester(member)).getStatus())
                .isEqualTo(204);
        assertThat(status(keyId)).isEqualTo("REVOKED");
        OffsetDateTime revokedAt = jdbcTemplate.queryForObject(
                "select revoked_at from llm_api_keys where id = ?", OffsetDateTime.class, keyId);
        assertThat(revokedAt).isNotNull();

        assertThat(call(revokeOp(), new Fixture(keyId, 0), requester(member)).getStatus())
                .isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "select revoked_at from llm_api_keys where id = ?", OffsetDateTime.class, keyId))
                .isEqualTo(revokedAt);
    }

    /**
     * A workspace-wide grant applies to a key the way it does to a VM: one row
     * naming the whole workspace opens the detail to a member the list never
     * names personally.
     */
    @Test
    void workspaceWideGrantOpensTheKeyToEveryMember() throws Exception {
        long keyId = insertKey();
        ScopedOp read = OPS.getFirst();

        MockHttpServletResponse refused = call(read, new Fixture(keyId, 0), requester(member));
        assertThat(refused.getStatus()).isEqualTo(403);

        grantLlmKeyToOwningWorkspace(jdbcTemplate, keyId, "VIEWER");
        MockHttpServletResponse opened = call(read, new Fixture(keyId, 0), requester(member));
        assertThat(opened.getStatus()).as("the workspace-wide grant decides (body: %s)",
                body(opened)).isEqualTo(200);
    }

    // ── driving one case ─────────────────────────────────────────────────────

    /** True when this standing should get past the access check for this op. */
    private static boolean allowed(ScopedOp scopedOp, Scenario scenario) {
        return switch (scenario) {
            case NON_MEMBER, MEMBER_WITHOUT_GRANT, GRANT_BELOW_RUNG -> false;
            case GRANT_AT_RUNG -> true;
            // A workspace owner's standing is exactly: manage the list and take
            // the key away. It is deliberately not a rung, so it carries neither
            // the detail read nor — above all — the mint: the plaintext is what
            // is inside this resource, and inside needs a grant.
            case WORKSPACE_OWNER_WITHOUT_GRANT -> MANAGED_OPS.contains(scopedOp.id());
        };
    }

    /** Writes the scenario's standing onto the fixture key and names the caller. */
    private Requester standingFor(ScopedOp scopedOp, Scenario scenario, Fixture fixture) {
        switch (scenario) {
            case GRANT_BELOW_RUNG -> grantLlmKeyToUser(jdbcTemplate, fixture.keyId(),
                    member.getId(), oneRungBelow(scopedOp.required()).name());
            case GRANT_AT_RUNG -> grantLlmKeyToUser(jdbcTemplate, fixture.keyId(), member.getId(),
                    scopedOp.required().name());
            default -> {
                // The other three standings are the absence of a grant.
            }
        }
        return switch (scenario) {
            case NON_MEMBER -> new Requester(outsider.getId(), outsiderToken);
            case WORKSPACE_OWNER_WITHOUT_GRANT ->
                    new Requester(workspaceOwner.getId(), workspaceOwnerToken);
            default -> new Requester(member.getId(), memberToken);
        };
    }

    /** OWNER → EDITOR → MEMBER → VIEWER; never called at the floor. */
    private static ResourceRole oneRungBelow(ResourceRole role) {
        ResourceRole[] rungs = ResourceRole.values();
        return rungs[role.ordinal() + 1];
    }

    private MockHttpServletResponse call(ScopedOp scopedOp, Fixture fixture, Requester requester)
            throws Exception {
        URI uri = URI.create("/api/v1" + resolve(scopedOp.path(), fixture));
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .request(scopedOp.method(), uri)
                .header("Authorization", "Bearer " + requester.token());
        if (scopedOp.reauth()) {
            request = request.header(ReauthTestSupport.HEADER,
                    ReauthTestSupport.seededReauthHeader(jdbcTemplate, requester.userId()));
        }
        if (scopedOp.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(resolve(scopedOp.body(), fixture));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** Fills the fixture's ids into a path or body template. */
    private String resolve(String template, Fixture fixture) {
        // The fixture builds its rows through SQL and holds their internal ids;
        // every path placeholder is the public one, because that is the only
        // thing the endpoints accept.
        String resolved = template
                .replace("{keyId}", String.valueOf(pub("llm_api_keys", fixture.keyId())))
                .replace("{spareUserId}", String.valueOf(spareBystander.getPublicId()));
        if (resolved.contains("{grantId}")) {
            resolved = resolved.replace("{grantId}",
                    String.valueOf(pub("resource_access_grants", fixture.grantId())));
        }
        return resolved;
    }

    private ScopedOp issueOp() {
        return opById("issueLlmKeyToken");
    }

    private ScopedOp revokeOp() {
        return opById("revokeLlmKey");
    }

    private static ScopedOp opById(String id) {
        return OPS.stream().filter(scopedOp -> scopedOp.id().equals(id)).findFirst().orElseThrow();
    }

    private Requester requester(User user) {
        return new Requester(user.getId(), jwtService.createAccessToken(user));
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(body(response)).get("code").asString();
    }

    private String body(MockHttpServletResponse response) throws Exception {
        response.setCharacterEncoding("UTF-8");
        return response.getContentAsString();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A key plus a spare USER grant for the {grantId} ops to act on. The spare
     * row deliberately names somebody else: a workspace-wide row here would
     * raise the requester's own rung and quietly turn the below-rung scenarios
     * into passes.
     */
    private Fixture newFixture() {
        long keyId = insertKey();
        grantLlmKeyToUser(jdbcTemplate, keyId, listedBystander.getId(), "VIEWER");
        long grantId = jdbcTemplate.queryForObject("""
                select id from resource_access_grants
                 where resource_type = 'LLM_API_KEY' and resource_id = ? and grantee_type = 'USER'
                   and user_id = ?
                """, Long.class, keyId, listedBystander.getId());
        return new Fixture(keyId, grantId);
    }

    /**
     * Inserted straight into the table, so no approval ever granted anyone: the
     * access list starts empty and each scenario writes exactly the standing it
     * means to test. PENDING with a null hash is the state between approval and
     * the owner's first mint — the one state every op here must handle, since
     * issue transitions out of it and revoke must reach it.
     */
    private long insertKey() {
        long requestId = RequestFixtures.insertLlmKeyRequest(jdbcTemplate, workspaceId, orgId,
                workspaceOwner.getId(), "접근 범위 테스트");
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          created_by)
                values (?, ?, ?, ?, '접근 범위 테스트', ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId,
                "scope-" + UUID.randomUUID().toString().substring(0, 12), workspaceOwner.getId());
    }

    private String status(long keyId) {
        return jdbcTemplate.queryForObject("select status from llm_api_keys where id = ?",
                String.class, keyId);
    }

    private String tokenHash(long keyId) {
        return jdbcTemplate.queryForObject("select token_hash from llm_api_keys where id = ?",
                String.class, keyId);
    }

    private long ensureWorkspace() {
        // Reused across the cases in this class, and workspaces carry no unique
        // key to upsert on any more, so this looks before it writes.
        List<Long> existing = jdbcTemplate.queryForList("""
                select id from workspaces
                 where name = 'LLM 키 접근 범위 테스트 팀' and deleted_at is null
                """, Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, 'LLM 키 접근 범위 테스트 팀')
                returning id
                """, Long.class);
    }

    private void addMember(long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    private User ensureUser(String email, String name, UserRole role, Long userOrgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(role);
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), userOrgId, role);
            return saved;
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
