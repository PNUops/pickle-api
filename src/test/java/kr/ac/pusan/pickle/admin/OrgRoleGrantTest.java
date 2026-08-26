package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * Per-organisation roles: the grant and revoke pair, and the operations that
 * have to read the role in <i>this</i> organisation rather than the account's
 * effective one.
 *
 * <p>The account that motivates all of it administers org A and merely operates
 * org B. Its effective role is ORG_ADMIN, which is what every
 * {@code @PreAuthorize} gate sees — so a gate admitting only ORG_ADMIN admits
 * this account when it acts on B, and the refusal has to come from the service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OrgRoleGrantTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org orgA;
    private Org orgB;
    private User adminOfA;
    private User bothOrgs;
    private User target;
    private User managerOfB;
    private User viewerOfA;
    private String sysAdminToken;
    private String adminOfAToken;
    private String bothOrgsToken;
    private String managerOfBToken;
    private String viewerOfAToken;

    @BeforeEach
    void setUp() {
        orgA = ensureOrg("역할부여 기관 A");
        orgB = ensureOrg("역할부여 기관 B");

        adminOfA = ensureUser("ogr.admin.a@pusan.ac.kr", "A기관관리자", UserRole.ORG_ADMIN);
        grant(adminOfA, orgA, UserRole.ORG_ADMIN);

        // Administers A, only operates B: the shape the per-org checks exist for.
        bothOrgs = ensureUser("ogr.both@pusan.ac.kr", "겸직관리자", UserRole.ORG_ADMIN);
        grant(bothOrgs, orgA, UserRole.ORG_ADMIN);
        grant(bothOrgs, orgB, UserRole.ORG_MANAGER);

        managerOfB = ensureUser("ogr.manager.b@pusan.ac.kr", "B기관운영자", UserRole.ORG_MANAGER);
        grant(managerOfB, orgB, UserRole.ORG_MANAGER);

        // What one organisation gives another's staff: sight of A, nothing more.
        viewerOfA = ensureUser("ogr.viewer.a@pusan.ac.kr", "A기관열람자", UserRole.ORG_VIEWER);
        grant(viewerOfA, orgA, UserRole.ORG_VIEWER);

        target = ensureUser("ogr.target@pusan.ac.kr", "대상", UserRole.USER);
        jdbcTemplate.update("delete from user_org_roles where user_id = ?", target.getId());
        target.setRole(UserRole.USER);
        target = userRepository.save(target);

        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        adminOfAToken = jwtService.createAccessToken(adminOfA);
        managerOfBToken = jwtService.createAccessToken(managerOfB);
        bothOrgsToken = jwtService.createAccessToken(bothOrgs);
        viewerOfAToken = jwtService.createAccessToken(viewerOfA);
    }

    @Test
    void grantsAccumulateAndRevokesTakeAwayOnlyTheNamedOrg() throws Exception {
        putRole(target, orgA, sysAdminToken, UserRole.ORG_MANAGER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_MANAGER"));
        putRole(target, orgB, sysAdminToken, UserRole.ORG_ADMIN)
                .andExpect(status().isOk())
                // the effective role is the highest one held anywhere
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());

        // revoking one leaves the other, and the effective role follows
        mockMvc.perform(delete(orgRolePath(target, orgB))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_MANAGER"));
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactly(orgA.getId());
    }

    @Test
    void revokingTheLastOrgDropsTheAccountToUserAndKillsItsTokens() throws Exception {
        putRole(target, orgA, sysAdminToken, UserRole.ORG_ADMIN).andExpect(status().isOk());
        User promoted = userRepository.findById(target.getId()).orElseThrow();
        String promotedToken = jwtService.createAccessToken(promoted);

        mockMvc.perform(delete(orgRolePath(target, orgA))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId())).isEmpty();

        // the role change invalidated the outstanding access token
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/me").header("Authorization", "Bearer " + promotedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anOrgAdminGrantsOnlyInsideTheOrgsItAdministers() throws Exception {
        putRole(target, orgA, adminOfAToken, UserRole.ORG_MANAGER).andExpect(status().isOk());

        // org B is not this account's to staff, and it does not get to learn
        // whether it exists: the same 404 an unknown org answers
        putRole(target, orgB, adminOfAToken, UserRole.ORG_ADMIN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete(orgRolePath(target, orgB))
                        .header("Authorization", "Bearer " + adminOfAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAccountThatOnlyOperatesAnOrgCannotStaffIt() throws Exception {
        // bothOrgs administers A and only operates B. Its effective role is
        // ORG_ADMIN, so the gate lets it through; B has to refuse it anyway.
        putRole(target, orgA, bothOrgsToken, UserRole.ORG_MANAGER).andExpect(status().isOk());
        putRole(target, orgB, bothOrgsToken, UserRole.ORG_MANAGER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anOrgAdminCannotTouchItselfOrASysTierAccount() throws Exception {
        putRole(adminOfA, orgA, adminOfAToken, UserRole.ORG_MANAGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        putRole(sysAdmin, orgA, adminOfAToken, UserRole.ORG_MANAGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aSysRoleCannotBeHandedOutThroughTheOrgRoleEndpoint() throws Exception {
        putRole(target, orgA, sysAdminToken, UserRole.SYS_ADMIN)
                .andExpect(status().isUnprocessableEntity());
        putRole(target, orgA, sysAdminToken, UserRole.USER)
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * The scheduled-deletion pair is ORG_ADMIN-only and its controller gate
     * cannot see which organisation the VM is in. Operating B is not enough.
     */
    @Test
    void scheduledDeletionAsksForTheRoleInTheVmsOwnOrg() throws Exception {
        long vmInB = createVmIn(orgB).vmId();
        long vmInA = createVmIn(orgA).vmId();

        scheduleDelete(vmInB, bothOrgsToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // an admin of another org only is refused the same way
        scheduleDelete(vmInB, adminOfAToken).andExpect(status().isNotFound());

        // and an ORG_MANAGER never reaches the handler at all — the controller
        // gate admits ORG_ADMIN and SYS_ADMIN only
        scheduleDelete(vmInA, managerOfBToken).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/vms/{id}/cancel-scheduled-delete", pub("vms", vmInA))
                        .header("Authorization", "Bearer " + managerOfBToken))
                .andExpect(status().isForbidden());

        // ...and the same account may do it in the org it does administer
        scheduleDelete(vmInA, bothOrgsToken).andExpect(status().isAccepted());
    }

    /**
     * A WORKSPACE announcement is ORG_ADMIN-only too, and its gate is the same
     * one — so the workspace has to be linked to an org this account
     * administers, not merely one it operates.
     */
    @Test
    void workspaceAnnouncementAsksForTheAdministeredOrgs() throws Exception {
        Fixture inB = createVmIn(orgB);
        Fixture inA = createVmIn(orgA);

        mockMvc.perform(post("/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + bothOrgsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "운영만 하는 기관", "body", "b", "scope", "WORKSPACE",
                                "workspaceId", pub("workspaces", inB.workspaceId()).toString()))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + bothOrgsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "관리하는 기관", "body", "b", "scope", "WORKSPACE",
                                "workspaceId", pub("workspaces", inA.workspaceId()).toString()))))
                .andExpect(status().isCreated());
    }

    private ResultActions scheduleDelete(long vmId, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/vms/{id}/schedule-delete", pub("vms", vmId))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "scheduledFor",
                        Instant.now().plus(java.time.Duration.ofDays(10)).toString(),
                        "reason", "예약 삭제"))));
    }

    // --- fixtures ---------------------------------------------------------------

    /**
     * The wholesale edit replaces every row with one. On an account holding
     * several, leaving either half out lets the server choose: which
     * organisation survives, or which role it survives with. The role it would
     * carry over is the account's HIGHEST, which in the organisation being
     * named may be a promotion nobody asked for.
     */
    @Test
    void theWholesaleEditRefusesToGuessOnAMultiOrgAccount() throws Exception {
        putRole(target, orgA, sysAdminToken, UserRole.ORG_ADMIN).andExpect(status().isOk());
        putRole(target, orgB, sysAdminToken, UserRole.ORG_MANAGER).andExpect(status().isOk());

        // organisation named, role left out: B would silently rise to ORG_ADMIN
        patchUser(Map.of("orgId", orgB.getPublicId().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());

        // role named, organisation left out: one of the two would be dropped
        patchUser(Map.of("role", "ORG_MANAGER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'orgId')]").exists());

        // neither row moved
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());
        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ORG_ADMIN);

        // naming both is accepted and replaces the set with the one named
        patchUser(Map.of("orgId", orgB.getPublicId().toString(), "role", "ORG_MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_MANAGER"));
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactly(orgB.getId());
    }

    private ResultActions patchUser(Map<String, String> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/users/" + target.getPublicId())
                .header("Authorization", "Bearer " + sysAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /**
     * The viewer role reads its organisation and changes nothing in it. The
     * gates admit it to the read surfaces by name, so what stops a write is the
     * gate itself rather than a service-layer check, and the two questions the
     * principal answers — may this account see this organisation, may it act in
     * it — have to disagree for exactly this account.
     */
    @Test
    void aViewerReadsItsOrganisationAndChangesNothing() throws Exception {
        assertThat(userRepository.findById(viewerOfA.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ORG_VIEWER);

        // reads it holds
        mockMvc.perform(get("/api/v1/admin/vms")
                        .header("Authorization", "Bearer " + viewerOfAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/summary")
                        .header("Authorization", "Bearer " + viewerOfAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + viewerOfAToken))
                .andExpect(status().isOk());

        // the audit log stays with the roles that may act
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", "Bearer " + viewerOfAToken))
                .andExpect(status().isForbidden());

        // writes in the very organisation it reads
        Fixture inA = createVmIn(orgA);
        mockMvc.perform(post("/api/v1/admin/vms/" + pub("vms", inA.vmId()) + "/shutdown")
                        .header("Authorization", "Bearer " + viewerOfAToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/users/" + target.getPublicId()
                        + "/org-roles/" + orgA.getPublicId())
                        .header("Authorization", "Bearer " + viewerOfAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("role", UserRole.ORG_VIEWER.name()))))
                .andExpect(status().isForbidden());
    }

    /** An organisation's administrator may hand out the viewer role in it. */
    @Test
    void anOrgAdminMayGrantTheViewerRoleInItsOwnOrg() throws Exception {
        putRole(target, orgA, adminOfAToken, UserRole.ORG_VIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_VIEWER"));
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactly(orgA.getId());
    }

    private ResultActions putRole(User user, Org org, String token, UserRole role)
            throws Exception {
        return mockMvc.perform(put(orgRolePath(user, org))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("role", role.name()))));
    }

    private String orgRolePath(User user, Org org) {
        return "/api/v1/admin/users/" + user.getPublicId() + "/org-roles/" + org.getPublicId();
    }

    private Org ensureOrg(String name) {
        return orgRepository.findFirstByNameOrderByIdAsc(name)
                .orElseGet(() -> orgRepository.save(new Org(name, null)));
    }

    private void grant(User user, Org org, UserRole role) {
        SeedFixtures.grantOrgRole(jdbcTemplate, user.getId(), org.getId(), role);
    }

    private User ensureUser(String email, String name, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    /** A workspace with one VM in the given org. */
    private record Fixture(long workspaceId, long vmId) {
    }

    private Fixture createVmIn(Org org) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject(
                "select id from nodes where name = 'pve1'", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, "ogr-" + UUID.randomUUID().toString().substring(0, 8));
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, org.getId(),
                requesterId, "역할부여 테스트", imageId);
        String hostname = "ogr-vm-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, org.getId(), requestId, hostname, hostname,
                imageId);
        return new Fixture(workspaceId, vmId);
    }

    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
