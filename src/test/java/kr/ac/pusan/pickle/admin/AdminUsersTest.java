package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
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
 * Admin user list/detail (ORG_ADMIN derived-org scoping, 404 masking) and
 * SYS_ADMIN disable/enable (self-guard, immediate token death, enable restores
 * the pre-disable status).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminUsersTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User sysAdmin;
    private User orgAdminA;
    private User memberA;
    private User foreign;
    private String sysAdminToken;
    private String orgAdminAToken;
    private String memberAToken;

    @BeforeEach
    void setUp() {
        Org orgA = orgRepository.findBySlug("au-org-a")
                .orElseGet(() -> orgRepository.save(new Org("사용자관리 기관 A", "au-org-a", null)));
        sysAdmin = ensureUser("au.sys@pusan.ac.kr", "시스템", UserRole.SYS_ADMIN, null, UserStatus.ACTIVE);
        orgAdminA = ensureUser("au.orga@pusan.ac.kr", "기관A관리자", UserRole.ORG_ADMIN, orgA.getId(),
                UserStatus.ACTIVE);
        memberA = ensureUser("au.member@pusan.ac.kr", "A소속원", UserRole.USER, null, UserStatus.ACTIVE);
        foreign = ensureUser("au.foreign@pusan.ac.kr", "외부인", UserRole.USER, null, UserStatus.ACTIVE);

        // memberA becomes derived-in-orgA: member of a workspace with a VM in orgA.
        if (workspaceMemberRepository.findWithWorkspaceByUserId(memberA.getId()).isEmpty()) {
            Workspace workspace = workspaceRepository.save(
                    new Workspace(WorkspaceKind.TEAM, "A팀", "au-team-" + UUID.randomUUID().toString().substring(0, 8),
                            null));
            workspaceMemberRepository.save(new WorkspaceMember(workspace, memberA.getId(), WorkspaceMemberRole.OWNER));
            createActiveVm(workspace.getId(), orgA.getId(), memberA.getId());
        }

        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        orgAdminAToken = jwtService.createAccessToken(orgAdminA);
        memberAToken = jwtService.createAccessToken(memberA);
    }

    @Test
    void listAndDetailAreScopedForOrgAdmin() throws Exception {
        // plain USER cannot use the admin surface
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // SYS_ADMIN sees every user (envelope shape)
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
        mockMvc.perform(get("/api/v1/admin/users?q=au.foreign@pusan.ac.kr")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // ORG_ADMIN sees the derived member but not the out-of-scope user
        mockMvc.perform(get("/api/v1/admin/users?q=au.member@pusan.ac.kr")
                        .header("Authorization", "Bearer " + orgAdminAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("au.member@pusan.ac.kr"))
                .andExpect(jsonPath("$.content[0].mfaEnabled").value(false));
        mockMvc.perform(get("/api/v1/admin/users?q=au.foreign@pusan.ac.kr")
                        .header("Authorization", "Bearer " + orgAdminAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // ORG_ADMIN detail: in-scope 200, out-of-scope masked as 404
        mockMvc.perform(get("/api/v1/admin/users/" + memberA.getId())
                        .header("Authorization", "Bearer " + orgAdminAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(memberA.getId()))
                .andExpect(jsonPath("$.activeVmCount").value(1))
                .andExpect(jsonPath("$.statusChanges").isArray());
        mockMvc.perform(get("/api/v1/admin/users/" + foreign.getId())
                        .header("Authorization", "Bearer " + orgAdminAToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // invalid sort → 422
        mockMvc.perform(get("/api/v1/admin/users?sort=bogus")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void disableAndEnableRestorePreviousStatus() throws Exception {
        User target = ensureUser("au.target@pusan.ac.kr", "대상", UserRole.USER, null, UserStatus.ACTIVE);
        String targetToken = jwtService.createAccessToken(target);

        // ORG_ADMIN cannot disable (SYS_ADMIN only)
        postJson("/api/v1/admin/users/" + target.getId() + "/disable", orgAdminAToken,
                Map.of("reason", "시도"))
                .andExpect(status().isForbidden());

        // self-disable is refused
        postJson("/api/v1/admin/users/" + sysAdmin.getId() + "/disable", sysAdminToken,
                Map.of("reason", "본인"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SELF_DISABLE_FORBIDDEN"));

        // disable → 200 DISABLED, target token dies immediately
        postJson("/api/v1/admin/users/" + target.getId() + "/disable", sysAdminToken,
                Map.of("reason", "자원 남용"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledReason").value("자원 남용"))
                .andExpect(jsonPath("$.statusChanges[0].toStatus").value("DISABLED"));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized());

        // disabling an already-DISABLED account → 409
        postJson("/api/v1/admin/users/" + target.getId() + "/disable", sysAdminToken,
                Map.of("reason", "다시"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVALID_STATE"));

        // enable restores ACTIVE and clears the disable stamp
        postJson("/api/v1/admin/users/" + target.getId() + "/enable", sysAdminToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.disabledAt").value((Object) null));

        // enabling a non-DISABLED account → 409 ACCOUNT_NOT_DISABLED
        postJson("/api/v1/admin/users/" + target.getId() + "/enable", sysAdminToken, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_DISABLED"));
    }

    @Test
    void enableRestoresPendingVerificationNotActive() throws Exception {
        User pending = ensureUser("au.pending@pusan.ac.kr", "미인증", UserRole.USER, null,
                UserStatus.PENDING_VERIFICATION);

        postJson("/api/v1/admin/users/" + pending.getId() + "/disable", sysAdminToken,
                Map.of("reason", "미인증 잠금"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        postJson("/api/v1/admin/users/" + pending.getId() + "/enable", sysAdminToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }

    @Test
    void mfaEnabledReflectsRealEnrollment() throws Exception {
        User enrolled = ensureUser("au.mfa@pusan.ac.kr", "이중인증", UserRole.USER, null, UserStatus.ACTIVE);
        // Live enrollment row (enabled_at not null = enrolled).
        jdbcTemplate.update(
                "insert into user_mfa (user_id, totp_secret_enc, enabled_at) values (?, 'enc', now()) "
                        + "on conflict (user_id) do update set enabled_at = now()",
                enrolled.getId());

        // list shows the real flag …
        mockMvc.perform(get("/api/v1/admin/users?q=au.mfa@pusan.ac.kr")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("au.mfa@pusan.ac.kr"))
                .andExpect(jsonPath("$.content[0].mfaEnabled").value(true));
        // … and so does the detail (drives the admin mfa-reset button)
        mockMvc.perform(get("/api/v1/admin/users/" + enrolled.getId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        // an un-enrolled user stays false in the detail view
        mockMvc.perform(get("/api/v1/admin/users/" + foreign.getId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    private void createActiveVm(long workspaceId, long orgId, long requesterId) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "사용자관리 테스트", imageId);
        String hostname = "au-vm-" + UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'RUNNING'::vm_status)
                """, nodeId, workspaceId, orgId, requestId, hostname, hostname, imageId);
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId, UserStatus status) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(status);
            if (status == UserStatus.ACTIVE) {
                user.setEmailVerifiedAt(Instant.now());
            }
            return userRepository.save(user);
        });
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
