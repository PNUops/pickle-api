package kr.ac.pusan.pickle.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Group management per contract: creation (TEAM/PROJECT only, unique slug),
 * member management role matrix (OWNER-only), ownership transfer, sole-owner
 * protection, self-leave and PERSONAL immutability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class GroupsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PersonalGroupService personalGroupService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User editor;
    private User member;
    private User outsider;
    private String ownerToken;
    private String editorToken;
    private String memberToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        owner = ensureUser("grp.owner@pusan.ac.kr", "그룹장", UserStatus.ACTIVE);
        editor = ensureUser("grp.manager@pusan.ac.kr", "매니저", UserStatus.ACTIVE);
        member = ensureUser("grp.member@pusan.ac.kr", "멤버", UserStatus.ACTIVE);
        outsider = ensureUser("grp.outsider@pusan.ac.kr", "외부인", UserStatus.ACTIVE);
        ensureUser("grp.pending@pusan.ac.kr", "미인증", UserStatus.PENDING_VERIFICATION);
        ownerToken = jwtService.createAccessToken(owner);
        editorToken = jwtService.createAccessToken(editor);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
    }

    /** Member management is sudo-mode gated: mint the caller's X-Reauth-Token. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    @Test
    void createValidatesKindAndSlugAndListsMyGroups() throws Exception {
        postJson("/api/v1/groups", ownerToken,
                Map.of("kind", "PROJECT", "name", "캡스톤 3조", "slug", "grp-create-x1",
                        "description", "2026-1 캡스톤디자인 3조"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PROJECT"))
                .andExpect(jsonPath("$.slug").value("grp-create-x1"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(owner.getId()))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // duplicate slug → 409 GROUP_SLUG_DUPLICATE
        postJson("/api/v1/groups", editorToken,
                Map.of("kind", "TEAM", "name", "다른 팀", "slug", "grp-create-x1"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("GROUP_SLUG_DUPLICATE"));

        // PERSONAL cannot be created manually → 422
        postJson("/api/v1/groups", ownerToken,
                Map.of("kind", "PERSONAL", "name", "개인", "slug", "grp-create-personal"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"));

        // slug charset/shape is validated
        postJson("/api/v1/groups", ownerToken,
                Map.of("kind", "TEAM", "name", "팀", "slug", "Bad_Slug!"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));

        // my-groups list carries myRole and memberCount
        mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'grp-create-x1')].myRole")
                        .value(org.hamcrest.Matchers.contains("OWNER")))
                .andExpect(jsonPath("$[?(@.slug == 'grp-create-x1')].memberCount")
                        .value(org.hamcrest.Matchers.contains(1)));

        // group.create is audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'group.create' and actor_id = ?",
                Long.class, owner.getId());
        assertThat(audits).isPositive();

        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/groups")).andExpect(status().isUnauthorized());
    }

    @Test
    void memberManagementIsOwnerOnly() throws Exception {
        long groupId = createGroup(ownerToken, "grp-members-x1");

        // OWNER adds EDITOR / MEMBER / VIEWER
        addMember(ownerToken, groupId, editor.getEmail(), "EDITOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(editor.getId()))
                .andExpect(jsonPath("$.role").value("EDITOR"));
        addMember(ownerToken, groupId, member.getEmail(), "MEMBER").andExpect(status().isCreated());

        // duplicate member → 409
        addMember(ownerToken, groupId, member.getEmail(), "MEMBER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_ALREADY_EXISTS"));

        // unknown email → 404 GROUP_MEMBER_USER_NOT_FOUND
        addMember(ownerToken, groupId, "no.such.user@pusan.ac.kr", "MEMBER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_USER_NOT_FOUND"));

        // non-ACTIVE users cannot be added → same 404
        addMember(ownerToken, groupId, "grp.pending@pusan.ac.kr", "MEMBER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_USER_NOT_FOUND"));

        // EDITOR cannot add members → 403 GROUP_MEMBER_MANAGE_FORBIDDEN
        addMember(editorToken, groupId, outsider.getEmail(), "VIEWER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));

        // OWNER role can only arrive via ownership transfer → 422
        addMember(ownerToken, groupId, outsider.getEmail(), "OWNER")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        // detail is member-only: member sees everyone, outsider gets 403
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].email").value(owner.getEmail()));
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/groups/999999").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void groupInfoUpdateAllowsOwnerAndManagerOnly() throws Exception {
        long groupId = createGroup(ownerToken, "grp-update-x1");
        addMember(ownerToken, groupId, editor.getEmail(), "EDITOR").andExpect(status().isCreated());
        addMember(ownerToken, groupId, member.getEmail(), "MEMBER").andExpect(status().isCreated());

        // OWNER may edit name/description
        patchJson("/api/v1/groups/" + groupId, ownerToken, Map.of("name", "새 이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"));

        // explicit null clears the description
        Map<String, Object> clearDescription = new HashMap<>();
        clearDescription.put("description", null);
        patchJson("/api/v1/groups/" + groupId, ownerToken, clearDescription)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value((Object) null));

        // EDITOR / MEMBER / outsider → 403, empty patch → 422 (contract: OWNER only)
        patchJson("/api/v1/groups/" + groupId, editorToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/groups/" + groupId, memberToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/groups/" + groupId, outsiderToken, Map.of("name", "몰래 수정"))
                .andExpect(status().isForbidden());
        patchJson("/api/v1/groups/" + groupId, ownerToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ownershipTransferAndSoleOwnerProtection() throws Exception {
        long groupId = createGroup(ownerToken, "grp-owner-x1");
        addMember(ownerToken, groupId, editor.getEmail(), "EDITOR").andExpect(status().isCreated());
        addMember(ownerToken, groupId, member.getEmail(), "VIEWER").andExpect(status().isCreated());

        // MEMBER-level users cannot change roles or remove others
        patchJson("/api/v1/groups/" + groupId + "/members/" + member.getId(), editorToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + editor.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, reauth(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));

        // OWNER promotes VIEWER → MEMBER
        patchJson("/api/v1/groups/" + groupId + "/members/" + member.getId(), ownerToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        // sole OWNER cannot demote or remove themselves
        patchJson("/api/v1/groups/" + groupId + "/members/" + owner.getId(), ownerToken,
                Map.of("role", "EDITOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_SOLE_OWNER_REMOVAL"));
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_SOLE_OWNER_REMOVAL"));

        // granting OWNER transfers ownership: previous OWNER becomes EDITOR
        patchJson("/api/v1/groups/" + groupId + "/members/" + editor.getId(), ownerToken,
                Map.of("role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.userId == %d)].role".formatted(owner.getId()))
                        .value(org.hamcrest.Matchers.contains("EDITOR")))
                .andExpect(jsonPath("$.members[?(@.userId == %d)].role".formatted(editor.getId()))
                        .value(org.hamcrest.Matchers.contains("OWNER")));

        // demoted previous owner can no longer manage members
        patchJson("/api/v1/groups/" + groupId + "/members/" + member.getId(), ownerToken,
                Map.of("role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));

        // non-OWNER may leave on their own
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, reauth(memberToken)))
                .andExpect(status().isNoContent());

        // new OWNER removes the demoted previous owner
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + editorToken)
                        .header(ReauthTestSupport.HEADER, reauth(editorToken)))
                .andExpect(status().isNoContent());

        // role change for someone who is not a member → 404
        patchJson("/api/v1/groups/" + groupId + "/members/" + member.getId(), editorToken,
                Map.of("role", "MEMBER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // membership changes are audit-logged
        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action in ('group.member_add', 'group.member_update', 'group.member_remove')
                   and target_id = ?
                """, Long.class, groupId);
        assertThat(audits).isGreaterThanOrEqualTo(6);
    }

    @Test
    void personalGroupMembershipIsImmutable() throws Exception {
        personalGroupService.ensurePersonalGroup(owner);
        var groups = objectMapper
                .readTree(mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + ownerToken))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        long personalGroupId = -1;
        for (int i = 0; i < groups.size(); i++) {
            if ("PERSONAL".equals(groups.get(i).path("kind").asString())) {
                personalGroupId = groups.get(i).path("id").asLong();
                break;
            }
        }
        assertThat(personalGroupId).isPositive();

        addMember(ownerToken, personalGroupId, editor.getEmail(), "MEMBER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));
        patchJson("/api/v1/groups/" + personalGroupId + "/members/" + owner.getId(), ownerToken,
                Map.of("role", "EDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/groups/" + personalGroupId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_MEMBER_MANAGE_FORBIDDEN"));
    }

    private long createGroup(String token, String slug) throws Exception {
        String body = postJson("/api/v1/groups", token,
                Map.of("kind", "TEAM", "name", "테스트 그룹 " + slug, "slug", slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions addMember(String token, long groupId, String email, String role) throws Exception {
        return postJson("/api/v1/groups/" + groupId + "/members", token, Map.of("email", email, "role", role));
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
