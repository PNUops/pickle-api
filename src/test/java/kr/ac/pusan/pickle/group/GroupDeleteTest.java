package kr.ac.pusan.pickle.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

/**
 * Group deletion (M6, contract {@code deleteGroup}): OWNER-only with non-member
 * 404 masking, PERSONAL and active-VM blockers, soft-delete semantics
 * (list/get exclusion + slug reuse), member notification and audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class GroupDeleteTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PersonalGroupService personalGroupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User editor;
    private User outsider;
    private String ownerToken;
    private String editorToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;

    @BeforeEach
    void setUp() {
        owner = ensureUser("gdel.owner@pusan.ac.kr", "삭제소유자");
        editor = ensureUser("gdel.editor@pusan.ac.kr", "삭제편집자");
        outsider = ensureUser("gdel.outsider@pusan.ac.kr", "외부인");
        ownerToken = jwtService.createAccessToken(owner);
        editorToken = jwtService.createAccessToken(editor);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
    }

    @Test
    void authorizationMatrixAndBlockers() throws Exception {
        String slug = "gdel-" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = createTeam(slug);
        addMember(groupId, editor.getEmail(), "EDITOR");

        // non-member → 404 (existence masked)
        mockMvc.perform(delete("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        // member below OWNER → 403
        mockMvc.perform(delete("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));

        // an active VM blocks deletion → 409
        long vmId = insertVm(groupId, "RUNNING");
        mockMvc.perform(delete("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_HAS_ACTIVE_VMS"));
        // even a DELETING VM still blocks
        jdbcTemplate.update("update vms set status = 'DELETING' where id = ?", vmId);
        mockMvc.perform(delete("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_HAS_ACTIVE_VMS"));
        // once destroyed (DELETED), deletion is allowed
        jdbcTemplate.update("update vms set status = 'DELETED' where id = ?", vmId);

        mockMvc.perform(delete("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        // gone from list and detail (404), audit + member notification recorded
        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='group.delete' and target_id=?",
                Long.class, groupId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from notifications where event='group.deleted' and user_id=?",
                Long.class, owner.getId())).isEqualTo(1L);

        // slug is reusable after soft-delete
        long reused = createTeam(slug);
        assertThat(reused).isNotEqualTo(groupId);
    }

    @Test
    void personalGroupIsUndeletable() throws Exception {
        personalGroupService.ensurePersonalGroup(owner);
        long personalId = jdbcTemplate.queryForObject("""
                select g.id from groups g join group_members gm on gm.group_id = g.id
                 where gm.user_id = ? and g.kind = 'PERSONAL'
                """, Long.class, owner.getId());
        mockMvc.perform(delete("/api/v1/groups/" + personalId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_PERSONAL_UNDELETABLE"));
    }

    private long insertVm(long groupId, String status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '그룹 삭제 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "gdel-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, status);
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "삭제 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "role", role))))
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
}
