package kr.ac.pusan.pickle.resource;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToOwningWorkspace;
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

    /**
     * The two halves of one rule, which only mean anything together: a list
     * nobody scoped carries what the caller may open, and naming the workspace
     * is what asks the other question and brings the rest back.
     *
     * <p>Asserted on both the inventory and the VM list because they are
     * supposed to be the same visibility reached two ways; if only the inventory
     * narrowed, the dashboard and the VM screen would disagree about what the
     * person has.
     */
    @Test
    void anUnscopedListCarriesOnlyWhatAGrantOpens() throws Exception {
        long vmId = createVm();
        String vmPublicId = pub("vms", vmId).toString();

        // Unscoped: the member holds no grant, so this is not their row.
        // Asserted on the row rather than on the total, because this account
        // carries grants from its sibling tests against the same database.
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')]").isEmpty());
        mockMvc.perform(get("/api/v1/vms")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')]").isEmpty());

        // Named, the same row is there — limited, and saying whom to ask. This is
        // where the person finds what the workspace holds and how to get in.
        mockMvc.perform(get("/api/v1/resources?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].ownerNames[0]")
                        .value(Matchers.contains(owner.getName())));

        // The requester's own row is theirs unscoped, through the OWNER grant
        // that seeding the resource gave them.
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(false)));

        // A grant is what moves the row into the member's own list.
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "VIEWER");
        mockMvc.perform(get("/api/v1/vms")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(false)));

        // The page envelope counts the rows it returns, asserted on an account
        // whose whole history is this test: two VMs in the workspace, a grant on
        // one. Narrowing applied after the page was counted would report two
        // elements here and hand back one.
        createVm();
        User newcomer = ensureUser("resindex.newcomer." + UUID.randomUUID() + "@pusan.ac.kr", "인벤토리신입");
        String newcomerToken = jwtService.createAccessToken(newcomer);
        addMember(workspaceId, newcomer.getEmail());
        grantVmToUser(jdbcTemplate, vmId, newcomer.getId(), "VIEWER");
        mockMvc.perform(get("/api/v1/vms")
                        .header("Authorization", "Bearer " + newcomerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /**
     * The workspace axis does not put a row in the unscoped list, and the
     * workspace-wide grant does.
     *
     * <p>Two rules that live nowhere else. A workspace owner's standing rights
     * are deliberately not a rung (operator, 2026-08-09), so "what do I hold"
     * must not answer with a resource they only administer — without this, an
     * owner of a class workspace is back to a default screen of other people's
     * machines, which is the thing this narrowing exists to stop. And the
     * grant that names nobody has to open the row for every member, or a
     * workspace that shares a VM with everyone loses it from their own lists.
     */
    @Test
    void standingRightsDoNotPlaceARowInTheUnscopedListButAWorkspaceWideGrantDoes()
            throws Exception {
        long vmId = createVm();
        String vmPublicId = pub("vms", vmId).toString();
        // The requester's seeded OWNER grant is what makes this their row, so it
        // goes: what is left is the workspace ownership alone.
        jdbcTemplate.update(
                "delete from resource_access_grants where resource_type = 'VM' and resource_id = ?",
                vmId);

        mockMvc.perform(get("/api/v1/vms")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')]").isEmpty());
        // Naming the workspace still shows it to them, restricted, with the way
        // back in: they may hand themselves or somebody else a grant.
        mockMvc.perform(get("/api/v1/vms?workspaceId=" + pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessManageAllowed")
                        .value(Matchers.contains(true)));

        // A grant naming nobody reaches every member, so it is their row too.
        grantVmToOwningWorkspace(jdbcTemplate, vmId, "MEMBER");
        mockMvc.perform(get("/api/v1/vms")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(false)));
        mockMvc.perform(get("/api/v1/resources")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + vmPublicId + "')].accessLimited")
                        .value(Matchers.contains(false)));
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
                        .value(Matchers.contains("VM #" + pub("vms", vmId))));
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
                                Map.of("kind", "PROJECT", "name", name))))
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
