package kr.ac.pusan.pickle.resource;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
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
import kr.ac.pusan.pickle.support.RequestFixtures;
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
 * The type-agnostic inventory, {@code GET /resources} (contract tag
 * {@code resources}).
 *
 * <p>The endpoint reuses the VM list rather than re-deriving visibility, so
 * what these tests are really about is that the reuse holds: the inventory must
 * not show a workspace the caller is outside of, and must not say more about a
 * resource than the VM list would. The restricted row is the sharp end — the
 * name it carries is a display label, never the SSH slug, which is the string
 * one types to reach the machine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ResourceIndexTest {

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

    private User owner;
    private User member;
    private User outsider;
    private String ownerToken;
    private String memberToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long imageId;
    private long workspaceId;
    private String workspaceName;

    @BeforeEach
    void setUp() throws Exception {
        owner = ensureUser("resindex.owner@pusan.ac.kr", "인벤토리소유자");
        member = ensureUser("resindex.member@pusan.ac.kr", "인벤토리구성원");
        outsider = ensureUser("resindex.outsider@pusan.ac.kr", "인벤토리외부인");
        ownerToken = jwtService.createAccessToken(owner);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        String slug = "resindex-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceName = "인벤토리 테스트 " + slug;
        workspaceId = createTeam(slug, workspaceName);
        addMember(workspaceId, member.getEmail());
    }

    @Test
    void inventoryListsTheWorkspaceRowsAMemberMaySee() throws Exception {
        long vmId = createVm();
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "VIEWER");
        String hostname = hostnameOf(vmId);

        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].type")
                        .value(Matchers.contains("VM")))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].name")
                        .value(Matchers.contains(hostname)))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].status")
                        .value(Matchers.contains("RUNNING")))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].workspaceName")
                        .value(Matchers.contains(workspaceName)))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].accessLimited")
                        .value(Matchers.contains(false)));

        // The untyped listing answers the same while VM is the only type, and
        // an explicit type filter narrows to it rather than changing the rows.
        mockMvc.perform(get("/api/v1/resources?type=VM&workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].name")
                        .value(Matchers.contains(hostname)));
    }

    @Test
    void aWorkspaceTheCallerIsOutsideOfIsAnEmptyPage() throws Exception {
        createVm();

        // No 403: the contract gives the listing no forbidden response, so a
        // workspace filter outside the caller's memberships simply matches
        // nothing — which also keeps the workspace's existence private.
        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        // And an id no workspace has answers the same way.
        mockMvc.perform(get("/api/v1/resources?workspaceId=" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aRowWithoutAGrantIsRestrictedAndCarriesNoSlug() throws Exception {
        // The member is in the workspace but on nobody's access list, so they
        // see the resource exists and no more.
        long vmId = createVm();
        String hostname = hostnameOf(vmId);

        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].accessLimited")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].ownerNames[0]")
                        .value(Matchers.contains(owner.getName())))
                // The whole point: the name a restricted row carries must not be
                // the slug, which would hand over the SSH address of a machine
                // this caller may not reach. With no display name set it falls
                // back to the id.
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].name")
                        .value(Matchers.contains("VM #" + vmId)));
        // Asserted against the whole body, not one field: the slug must not
        // appear anywhere in the response, whichever field might carry it.
        String body = mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(hostname);

        // A display name is what the row shows when there is one — it is the
        // label the workspace chose, and it is not an address.
        setDisplayName(vmId, "연구용 서버");
        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].name")
                        .value(Matchers.contains("연구용 서버")));

        // Granted, the same row opens and the slug comes back with it.
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "VIEWER");
        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].accessLimited")
                        .value(Matchers.contains(false)))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].name")
                        .value(Matchers.contains(hostname)))
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("vms", vmId) + "\')].displayName")
                        .value(Matchers.contains("연구용 서버")));
    }

    @Test
    void unauthenticatedCallIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/resources"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long createVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                owner.getId(), "인벤토리 테스트", imageId, 1, 1024, 10);
        String hostname = "resindex-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10,
                        (select coalesce(max(proxmox_vmid), 100000) + 1 from vms), 'RUNNING')
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId);
        // Approval is bypassed here, so the access list is written by hand: the
        // requester owns it and nobody else is named.
        grantVmToUser(jdbcTemplate, vmId, owner.getId(), "OWNER");
        return vmId;
    }

    /** The SSH slug of a VM — what a restricted row must never contain. */
    private String hostnameOf(long vmId) {
        return jdbcTemplate.queryForObject("select hostname from vms where id = ?", String.class,
                vmId);
    }

    private void setDisplayName(long vmId, String displayName) {
        jdbcTemplate.update("""
                insert into vm_settings (vm_id, key, value, updated_at)
                values (?, 'display_name', to_jsonb(?::text), now())
                """, vmId, displayName);
    }

    private long createTeam(String slug, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "workspaces", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
    }

    private void addMember(long workspaceId, String email) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + pub("workspaces", workspaceId) + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, ReauthTestSupport.seededReauthFor(
                                jdbcTemplate, jwtService, ownerToken))
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
