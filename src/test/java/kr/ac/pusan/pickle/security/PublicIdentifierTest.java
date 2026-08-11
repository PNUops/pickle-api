package kr.ac.pusan.pickle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The identifier boundary itself: what the API hands out, what it accepts back,
 * and that swapping a numeric id in for a public one buys nothing.
 *
 * <p>The masking rules have their own suites ({@link VmAccessScopingTest} and
 * the per-surface tests). What is asserted here is the property those suites
 * assume — that an id which arrives over HTTP is resolved against
 * {@code public_id} and against nothing else, so that an internal row number is
 * neither a way in nor a thing the API ever says out loud.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PublicIdentifierTest {

    /** The shape a UUID renders as, which is what every exposed id must match. */
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User owner;
    private User outsider;
    private String ownerToken;
    private String outsiderToken;
    private long workspaceId;
    private long foreignWorkspaceId;
    private long vmId;
    private long foreignVmId;

    @BeforeEach
    void setUp() {
        owner = ensureUser("pubid.owner@pusan.ac.kr", "공개식별자소유자");
        outsider = ensureUser("pubid.outsider@pusan.ac.kr", "공개식별자외부인");
        ownerToken = jwtService.createAccessToken(owner);
        outsiderToken = jwtService.createAccessToken(outsider);
        workspaceId = ensureWorkspace("공개 식별자 워크스페이스", owner.getId());
        foreignWorkspaceId = ensureWorkspace("공개 식별자 타 워크스페이스", outsider.getId());
        vmId = createVm(workspaceId, "pubid-own");
        foreignVmId = createVm(foreignWorkspaceId, "pubid-foreign");
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, owner.getId(), "OWNER");
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, foreignVmId, outsider.getId(), "OWNER");
    }

    @Test
    void everyIdTheApiHandsBackIsAUuidAndNeverTheRowNumber() throws Exception {
        UUID vmPublicId = SeedFixtures.publicId(jdbcTemplate, "vms", vmId);

        mockMvc.perform(get("/api/v1/vms/" + vmPublicId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vmPublicId.toString()))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.workspaceId")
                        .value(org.hamcrest.Matchers.matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.orgId")
                        .value(org.hamcrest.Matchers.matchesPattern(UUID_PATTERN)));

        // The row number is nowhere in the payload — not as the id, not beside it.
        String body = mockMvc.perform(get("/api/v1/vms/" + vmPublicId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"id\":" + vmId);
    }

    @Test
    void theRowNumberIsNoLongerAnAddress() throws Exception {
        // A well-formed bigint is not a well-formed identifier any more, so the
        // path never reaches the VM it used to name: the type conversion refuses
        // it first, which is a 422 rather than the old 404.
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anIdentifierNoRowHasAnswersTheSame404AnUnknownNumberDid() throws Exception {
        mockMvc.perform(get("/api/v1/vms/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/vms/" + SeedFixtures.UNKNOWN_ID + "/events")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/workspaces/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anotherWorkspacesResourceIsRefusedExactlyAsItsAbsenceWouldBe() throws Exception {
        UUID foreignVmPublicId = SeedFixtures.publicId(jdbcTemplate, "vms", foreignVmId);

        // Holding the real identifier of somebody else's VM is worth no more
        // than holding one nobody has: both answers are the same 404, so the
        // identifier itself discloses nothing about what exists.
        var foreign = refusal(foreignVmPublicId);
        var unknown = refusal(SeedFixtures.UNKNOWN_ID);
        // Everything but the echoed request path, which is the caller's own
        // input and tells them nothing they did not already know.
        assertThat(foreign).isEqualTo(unknown);
    }

    @Test
    void theAccessTokenNamesItsAccountByPublicIdAndCarriesNoOrg() {
        var claims = jwtService.parse(jwtService.createAccessToken(owner));

        assertThat(claims.getSubject()).isEqualTo(owner.getPublicId().toString());
        // org_id was written and never read, and it put the org's row number in
        // a token any client can decode.
        assertThat(claims).doesNotContainKey("org_id");
    }

    /** The refusal a VM read produces, with the echoed request path removed. */
    private String refusal(UUID vmPublicId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/vms/" + vmPublicId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        return body.replace(vmPublicId.toString(), "<id>");
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode("Pickle!2345"), name);
            user.setStatus(UserStatus.ACTIVE);
            return userRepository.save(user);
        });
    }

    private long ensureWorkspace(String name, long ownerId) {
        List<Long> existing = jdbcTemplate.queryForList(
                "select id from workspaces where name = ?", Long.class, name);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        long id = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, name);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, id, ownerId);
        return id;
    }

    private long createVm(long owningWorkspaceId, String hostname) {
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('VM', ?, ?, ?, '공개 식별자 확인', '공개 식별자 확인')
                returning id
                """, Long.class, owningWorkspaceId, orgId, owner.getId());
        String unique = hostname + "-" + Instant.now().toEpochMilli();
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname, image_id,
                                 vcpu, memory_mb, disk_gb, ssh_username, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, 'ubuntu', 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, owningWorkspaceId, orgId, requestId, unique, unique,
                imageId);
    }
}
