package kr.ac.pusan.pickle.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Workspace management per contract: creation (TEAM/PROJECT only),
 * member management role matrix (OWNER-only), owner appointment and release,
 * last-owner protection, self-leave and PERSONAL immutability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class WorkspacesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PersonalWorkspaceService personalWorkspaceService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User peer;
    private User member;
    private User outsider;
    private String ownerToken;
    private String peerToken;
    private String memberToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        owner = ensureUser("grp.owner@pusan.ac.kr", "워크스페이스장", UserStatus.ACTIVE);
        peer = ensureUser("grp.peer@pusan.ac.kr", "동료", UserStatus.ACTIVE);
        member = ensureUser("grp.member@pusan.ac.kr", "멤버", UserStatus.ACTIVE);
        outsider = ensureUser("grp.outsider@pusan.ac.kr", "외부인", UserStatus.ACTIVE);
        ensureUser("grp.pending@pusan.ac.kr", "미인증", UserStatus.PENDING_VERIFICATION);
        ownerToken = jwtService.createAccessToken(owner);
        peerToken = jwtService.createAccessToken(peer);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
    }

    /** Member management is sudo-mode gated: mint the caller's X-Reauth-Token. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    @Test
    void createValidatesKindAndListsMyWorkspaces() throws Exception {
        postJson("/api/v1/workspaces", ownerToken,
                Map.of("kind", "PROJECT", "name", "캡스톤 3조",
                        "description", "2026-1 캡스톤디자인 3조"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PROJECT"))
                .andExpect(jsonPath("$.name").value("캡스톤 3조"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(owner.getId()))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // A name is not a key: the same one twice is two workspaces, since
        // nothing addresses a workspace by name.
        postJson("/api/v1/workspaces", peerToken,
                Map.of("kind", "PROJECT", "name", "캡스톤 3조"))
                .andExpect(status().isCreated());

        // PERSONAL cannot be created manually → 422
        postJson("/api/v1/workspaces", ownerToken,
                Map.of("kind", "PERSONAL", "name", "개인"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"));

        // a blank name is refused
        postJson("/api/v1/workspaces", ownerToken,
                Map.of("kind", "TEAM", "name", " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        // my-workspaces list carries myRole and memberCount
        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '캡스톤 3조')].myRole")
                        .value(org.hamcrest.Matchers.contains("OWNER")))
                .andExpect(jsonPath("$[?(@.name == '캡스톤 3조')].memberCount")
                        .value(org.hamcrest.Matchers.contains(1)));

        // workspace.create is audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'workspace.create' and actor_id = ?",
                Long.class, owner.getId());
        assertThat(audits).isPositive();

        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized());
    }

    @Test
    void memberManagementIsOwnerOnly() throws Exception {
        long workspaceId = createWorkspace(ownerToken, "grp-members-x1");

        // OWNER adds members: MEMBER is the only rung an addition may name
        addMember(ownerToken, workspaceId, peer.getEmail(), "MEMBER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(peer.getId()))
                .andExpect(jsonPath("$.role").value("MEMBER"));
        addMember(ownerToken, workspaceId, member.getEmail(), "MEMBER").andExpect(status().isCreated());

        // EDITOR and VIEWER belong to the per-resource access list, not to the
        // workspace axis, so the workspace API no longer knows the words → 422
        addMember(ownerToken, workspaceId, outsider.getEmail(), "EDITOR")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        addMember(ownerToken, workspaceId, outsider.getEmail(), "VIEWER")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // duplicate member → 409
        addMember(ownerToken, workspaceId, member.getEmail(), "MEMBER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_ALREADY_EXISTS"));

        // unknown email → 404 WORKSPACE_MEMBER_USER_NOT_FOUND
        addMember(ownerToken, workspaceId, "no.such.user@pusan.ac.kr", "MEMBER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_USER_NOT_FOUND"));

        // non-ACTIVE users cannot be added → same 404
        addMember(ownerToken, workspaceId, "grp.pending@pusan.ac.kr", "MEMBER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_USER_NOT_FOUND"));

        // a plain member cannot add members → 403 WORKSPACE_MEMBER_MANAGE_FORBIDDEN
        addMember(peerToken, workspaceId, outsider.getEmail(), "MEMBER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));

        // OWNER is appointed by a role change, never by an addition → 422
        addMember(ownerToken, workspaceId, outsider.getEmail(), "OWNER")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        // detail is member-only: member sees everyone, outsider gets 403
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].email").value(owner.getEmail()));
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/workspaces/999999").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void workspaceInfoUpdateIsOwnerOnly() throws Exception {
        long workspaceId = createWorkspace(ownerToken, "grp-update-x1");
        addMember(ownerToken, workspaceId, peer.getEmail(), "MEMBER").andExpect(status().isCreated());
        addMember(ownerToken, workspaceId, member.getEmail(), "MEMBER").andExpect(status().isCreated());

        // OWNER may edit name/description
        patchJson("/api/v1/workspaces/" + workspaceId, ownerToken, Map.of("name", "새 이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"));

        // explicit null clears the description
        Map<String, Object> clearDescription = new HashMap<>();
        clearDescription.put("description", null);
        patchJson("/api/v1/workspaces/" + workspaceId, ownerToken, clearDescription)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value((Object) null));

        // members / outsider → 403, empty patch → 422 (contract: OWNER only)
        patchJson("/api/v1/workspaces/" + workspaceId, peerToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/workspaces/" + workspaceId, memberToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/workspaces/" + workspaceId, outsiderToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden());
        patchJson("/api/v1/workspaces/" + workspaceId, ownerToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ownerAppointmentAndLastOwnerProtection() throws Exception {
        long workspaceId = createWorkspace(ownerToken, "grp-owner-x1");
        addMember(ownerToken, workspaceId, peer.getEmail(), "MEMBER").andExpect(status().isCreated());
        addMember(ownerToken, workspaceId, member.getEmail(), "MEMBER").andExpect(status().isCreated());

        // plain members cannot change roles or remove others
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + member.getId(), peerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + peer.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, reauth(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));

        // the last owner can neither step down nor leave: the workspace would be
        // left with nobody who can add members or appoint a replacement
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + owner.getId(), ownerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SOLE_OWNER_REMOVAL"));
        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_SOLE_OWNER_REMOVAL"));

        // ownership is appointed, not handed over: both are OWNER afterwards
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + peer.getId(), ownerToken,
                Map.of("role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.userId == %d)].role".formatted(owner.getId()))
                        .value(org.hamcrest.Matchers.contains("OWNER")))
                .andExpect(jsonPath("$.members[?(@.userId == %d)].role".formatted(peer.getId()))
                        .value(org.hamcrest.Matchers.contains("OWNER")));

        // appointing somebody costs the appointer nothing — they still manage
        addMember(ownerToken, workspaceId, outsider.getEmail(), "MEMBER")
                .andExpect(status().isCreated());

        // with a second owner in place the first may now release ownership
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + owner.getId(), ownerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        // and from then on manages nothing
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + member.getId(), ownerToken,
                Map.of("role", "OWNER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));

        // non-OWNER may leave on their own
        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, reauth(memberToken)))
                .andExpect(status().isNoContent());

        // the remaining owner removes the one who released ownership
        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + peerToken)
                        .header(ReauthTestSupport.HEADER, reauth(peerToken)))
                .andExpect(status().isNoContent());

        // role change for someone who is not a member → 404
        patchJson("/api/v1/workspaces/" + workspaceId + "/members/" + member.getId(), peerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // membership changes are audit-logged
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action in ('workspace.member_add', 'workspace.member_update', 'workspace.member_remove')
                   and target_id = ?
                """, Long.class, workspaceId);
        assertThat(audits).isGreaterThanOrEqualTo(6);
    }

    @Test
    void personalWorkspaceMembershipIsImmutable() throws Exception {
        personalWorkspaceService.ensurePersonalWorkspace(owner);
        var workspaces = objectMapper
                .readTree(mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + ownerToken))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        long personalWorkspaceId = -1;
        for (int i = 0; i < workspaces.size(); i++) {
            if ("PERSONAL".equals(workspaces.get(i).path("kind").asString())) {
                personalWorkspaceId = workspaces.get(i).path("id").asLong();
                break;
            }
        }
        assertThat(personalWorkspaceId).isPositive();

        addMember(ownerToken, personalWorkspaceId, peer.getEmail(), "MEMBER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
        patchJson("/api/v1/workspaces/" + personalWorkspaceId + "/members/" + owner.getId(), ownerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/workspaces/" + personalWorkspaceId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
    }

    private long createWorkspace(String token, String slug) throws Exception {
        String body = postJson("/api/v1/workspaces", token,
                Map.of("kind", "TEAM", "name", "테스트 워크스페이스 " + slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions addMember(String token, long workspaceId, String email, String role) throws Exception {
        return postJson("/api/v1/workspaces/" + workspaceId + "/members", token, Map.of("email", email, "role", role));
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions patchJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch(uri)
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private User ensureUser(String email, String name, UserStatus status) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(status);
            if (status == UserStatus.ACTIVE) {
                user.setEmailVerifiedAt(Instant.now());
            }
            return userRepository.save(user);
        });
    }
}
