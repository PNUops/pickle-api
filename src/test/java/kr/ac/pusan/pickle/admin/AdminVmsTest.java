package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /admin/vms} per contract: ORG_ADMIN hard-scoped to their own org
 * (another org in the orgId filter answers 404 so cross-org existence stays
 * private), SYS_ADMIN across orgs, the orgId/workspaceId/status filters, paging,
 * and the VmSummary shape. Assertions are per-id (the database is shared
 * with the other admin tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org org;
    private Org otherOrg;
    private String userToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String sysAdminToken;
    private long workspaceA1;
    private long workspaceA2;
    private long workspaceB;
    private long vmRunningA1;
    private long vmStoppedA2;
    private long vmRunningB;

    @BeforeEach
    void setUp() {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("VM 목록 타기관").orElseGet(() ->
                orgRepository.save(new Org("VM 목록 타기관", null)));
        User regularUser = ensureUser("advm.user@pusan.ac.kr", "목록학생", UserRole.USER, null);
        User otherOrgAdmin = ensureUser("advm.other.admin@pusan.ac.kr", "타기관관리자",
                UserRole.ORG_ADMIN, otherOrg.getId());
        userToken = jwtService.createAccessToken(regularUser);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        workspaceA1 = createWorkspace();
        workspaceA2 = createWorkspace();
        workspaceB = createWorkspace();
        vmRunningA1 = createVm(org.getId(), workspaceA1, "RUNNING");
        vmStoppedA2 = createVm(org.getId(), workspaceA2, "STOPPED");
        vmRunningB = createVm(otherOrg.getId(), workspaceB, "RUNNING");
    }

    @Test
    void theOrgTierListsEveryOrgAndTheOrgIdParamOnlyFilters() throws Exception {
        // users have no admin VM list → 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // every org, unfiltered (2026-08-25)
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningB)).exists());
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + org.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists())
                .andExpect(jsonPath(byId(vmRunningB)).doesNotExist());

        // another org in the filter is now a filter, not a 404: the org tier
        // reads every org, so naming one narrows rather than refuses.
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningB)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());

        // the other org's admin reads the same platform-wide list
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningB)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).exists());
    }

    @Test
    void sysAdminSeesAllOrgsAndMayFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningA1)).exists())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningB)).exists());
        mockMvc.perform(get("/api/v1/admin/vms?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmRunningB)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/vms?workspaceId=" + pub("workspaces", workspaceA2))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/admin/vms?orgId=%s&status=STOPPED".formatted(org.getPublicId()))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(vmStoppedA2)).exists())
                .andExpect(jsonPath(byId(vmRunningA1)).doesNotExist());
    }

    @Test
    void returnsTheVmSummaryShapeAndPages() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vms?workspaceId=" + pub("workspaces", workspaceA1))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pub("vms", vmRunningA1).toString()))
                .andExpect(jsonPath("$.content[0].name").isNotEmpty())
                .andExpect(jsonPath("$.content[0].hostname").isNotEmpty())
                .andExpect(jsonPath("$.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].vcpu").value(2))
                .andExpect(jsonPath("$.content[0].memoryMb").value(2048))
                .andExpect(jsonPath("$.content[0].diskGb").value(10))
                .andExpect(jsonPath("$.content[0].workspaceId").value(pub("workspaces", workspaceA1).toString()))
                .andExpect(jsonPath("$.content[0].requestId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        // paging: size=1 over the two org-A workspaces → 2 pages, newest first
        mockMvc.perform(get("/api/v1/admin/vms?orgId=%s&size=1".formatted(org.getPublicId()))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1));

        // page/size validation is the shared 422 handler's business
        mockMvc.perform(get("/api/v1/admin/vms?size=0")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void searchesByNamePartialMatchWithLikeEscaping() throws Exception {
        // 이 메서드 전용 접두사 — DB가 다른 테스트 메서드와 공유되므로 이름을 격리한다.
        long alpha = createVm(org.getId(), workspaceA1, "RUNNING", "adv-qsearch-alpha");
        long underscore = createVm(org.getId(), workspaceA1, "RUNNING", "adv-q-under_score");
        long noUnderscore = createVm(org.getId(), workspaceA1, "RUNNING", "adv-q-underXscore");

        // case-insensitive partial match on name
        mockMvc.perform(get("/api/v1/admin/vms?q=QSEARCH-AL")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(alpha)).exists())
                .andExpect(jsonPath("$.content.length()").value(1));

        // hostname is searched too — alpha's custom name doesn't contain the
        // seeded hostname prefix, so this hit proves the hostname column
        mockMvc.perform(get("/api/v1/admin/vms?q=advm-vm-&workspaceId=" + pub("workspaces", workspaceA1))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(alpha)).exists());

        // '_' must match literally, not as a single-char wildcard
        mockMvc.perform(get("/api/v1/admin/vms?q=q-under_score")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(underscore)).exists())
                .andExpect(jsonPath(byId(noUnderscore)).doesNotExist());
    }

    @Test
    void sortsByWhitelistAndRejectsUnknownKeys() throws Exception {
        long alpha = createVm(org.getId(), workspaceA1, "RUNNING", "adv-sortsearch-alpha");
        long bravo = createVm(org.getId(), workspaceA1, "RUNNING", "adv-sortsearch-bravo");

        mockMvc.perform(get("/api/v1/admin/vms?q=sortsearch&sort=name")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pub("vms", alpha).toString()))
                .andExpect(jsonPath("$.content[1].id").value(pub("vms", bravo).toString()));

        mockMvc.perform(get("/api/v1/admin/vms?q=sortsearch&sort=-name")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pub("vms", bravo).toString()))
                .andExpect(jsonPath("$.content[1].id").value(pub("vms", alpha).toString()));

        // default stays newest-first when sort is omitted
        mockMvc.perform(get("/api/v1/admin/vms?q=sortsearch")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pub("vms", bravo).toString()));

        // arbitrary property names never reach the ORM
        mockMvc.perform(get("/api/v1/admin/vms?sort=initialPassword")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    private String byId(long vmId) {
        return "$.content[?(@.id == '%s')]".formatted(pub("vms", vmId));
    }

    private long createWorkspace() {
        String slug = "advm-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    private long createVm(long orgId, long workspaceId, String status) {
        return createVm(orgId, workspaceId, status, null);
    }

    /** Minimal request→vm FK chain (2 vCPU / 2048 MiB / 10 GiB). */
    private long createVm(long orgId, long workspaceId, String status, String name) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "관리자 목록 테스트", imageId);
        String hostname = "advm-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId,
                name != null ? name : hostname, hostname, imageId, status);
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
            return saved;
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
