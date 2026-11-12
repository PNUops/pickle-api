package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminPerformanceOverviewTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JwtService jwt;
    @Autowired private AdminPerformanceOverviewService service;

    @Test
    void hardwareAndGlobalAccountCountsRetainTheirDistinctSystemRoleBoundaries() throws Exception {
        mvc.perform(post("/internal/platform-hardware-observations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        for (UserRole role : new UserRole[] {UserRole.USER, UserRole.ORG_VIEWER, UserRole.ORG_MANAGER,
                UserRole.ORG_ADMIN, UserRole.SYS_VIEWER}) {
            mvc.perform(get("/api/v1/admin/performance/overview")
                    .header("Authorization", "Bearer " + jwt.createAccessToken(user(role))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/performance/overview")
                .header("Authorization", "Bearer " + jwt.createAccessToken(user(UserRole.SYS_MANAGER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hardware").isEmpty())
                .andExpect(jsonPath("$.business").isEmpty());
        mvc.perform(get("/api/v1/admin/performance/overview")
                .header("Authorization", "Bearer " + jwt.createAccessToken(user(UserRole.SYS_ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hardware").isEmpty())
                .andExpect(jsonPath("$.business.registeredAccounts").isNumber());
    }

    @Test
    void currentAndHistoricalBusinessFactsUseTheirActualLifecycleBoundaries() {
        User admin = user(UserRole.SYS_ADMIN);
        AuthenticatedUser actor = new AuthenticatedUser(admin.getId(), admin.getPublicId(), admin.getEmail(),
                UserRole.SYS_ADMIN, java.util.Map.of());
        var before = service.get(actor).business();
        User registered = user(UserRole.USER);
        User withdrawn = user(UserRole.USER);
        withdrawn.setWithdrawnAt(Instant.now()); users.save(withdrawn);
        Org org = orgs.save(new Org("Overview synthetic institution " + UUID.randomUUID(), null));
        Workspace project = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Overview project", null));
        Workspace course = workspaces.save(new Workspace(WorkspaceKind.COURSE, "Overview course", null));
        Workspace removed = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Overview removed", null));
        jdbc.update("update workspaces set deleted_at=now() where id=?", removed.getId());
        long approvedVm = request("VM", "APPROVED", project, org, registered);
        request("LLM_API_KEY", "APPROVED", course, org, registered);
        request("VM", "CANCELED", project, org, registered);
        for (String state : new String[] {"RUNNING", "DELETED"}) {
            jdbc.update("""
                    insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                    values((select id from nodes order by id limit 1),?,?,?,'Overview VM',?,
                           (select id from os_images order by id limit 1),1,1024,10,?::vm_status)
                    """, project.getId(), org.getId(), approvedVm, "overview-" + UUID.randomUUID(), state);
        }
        for (String state : new String[] {"ACTIVE", "REMOVED"}) {
            jdbc.update("""
                    insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                    values(?,?,'EXTERNAL',?,'example.invalid',?::domain_status,now()+interval '30 days')
                    """, project.getId(), org.getId(), UUID.randomUUID()+".example.invalid", state);
        }
        var after = service.get(actor).business();
        assertThat(after.registeredAccounts()).isEqualTo(before.registeredAccounts()+1);
        assertThat(after.currentWorkspaces()).isEqualTo(before.currentWorkspaces()+2);
        assertThat(after.workspacesByKind().getOrDefault("PROJECT",0L))
                .isEqualTo(before.workspacesByKind().getOrDefault("PROJECT",0L)+1);
        assertThat(after.workspacesByKind().getOrDefault("COURSE",0L))
                .isEqualTo(before.workspacesByKind().getOrDefault("COURSE",0L)+1);
        assertThat(after.approvedRequests()).isEqualTo(before.approvedRequests()+2);
        assertThat(after.approvedRequestsByKind().getOrDefault("VM",0L))
                .isEqualTo(before.approvedRequestsByKind().getOrDefault("VM",0L)+1);
        assertThat(after.vmRegistrations()).isEqualTo(before.vmRegistrations()+2);
        assertThat(after.registeredDomains()).isEqualTo(before.registeredDomains()+1);
    }

    private long request(String kind, String state, Workspace workspace, Org org, User user) {
        return jdbc.queryForObject("""
                insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name,status)
                values(?::resource_type,?,?,?,'Synthetic overview','Overview request',?::request_status) returning id
                """, Long.class, kind, workspace.getId(), org.getId(), user.getId(), state);
    }

    private User user(UserRole role) {
        User user = new User("overview-"+UUID.randomUUID()+"@pusan.ac.kr", "{test-no-login}", "Overview actor");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now());
        return users.save(user);
    }
}
