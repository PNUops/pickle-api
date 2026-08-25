package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The daily allocation history, reconstructed from the VM rows themselves.
 *
 * <p>Every assertion here is org-filtered: the trend is a platform-wide query
 * without one, and the rows other suites leave in the shared database would
 * make exact counts impossible. The interesting cases are the two day
 * boundaries — a VM born at KST midnight belongs to that day and not the one
 * before, a VM deleted at KST midnight belongs to the day before and not that
 * one — because an off-by-one there is invisible in the totals and wrong in
 * every chart drawn from them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class CapacityTrendTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
    private String orgAdminToken;
    private String sysAdminToken;
    private String userToken;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        String slug = "cap-" + UUID.randomUUID().toString().substring(0, 8);
        org = orgRepository.save(new Org("추이 테스트 기관 " + slug, null));
        User orgAdmin = createUser("cap.admin." + slug + "@pusan.ac.kr", UserRole.ORG_ADMIN,
                org.getId());
        User regularUser = createUser("cap.user." + slug + "@pusan.ac.kr", UserRole.USER, null);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        userToken = jwtService.createAccessToken(regularUser);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    @Test
    void everyDayCountsTheVmsThatWereAliveWhenItEnded() throws Exception {
        LocalDate today = LocalDate.now(KST);
        // Alive the whole window.
        createVm(org.getId(), midnight(today.minusDays(10)), null, "RUNNING");
        // Born exactly at the start of today-3: counts from that day on.
        createVm(org.getId(), midnight(today.minusDays(3)), null, "RUNNING");
        // Deleted exactly at the start of today-2: its last day is today-3.
        createVm(org.getId(), midnight(today.minusDays(10)), midnight(today.minusDays(2)),
                "DELETED");
        // Another org's VM never appears in this org's series.
        createVm(SeedFixtures.seedOrgId(jdbcTemplate), midnight(today.minusDays(10)), null,
                "RUNNING");

        mockMvc.perform(get("/api/v1/admin/capacity-trend?days=7&orgId=" + org.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(6).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.points[0].day").value(today.minusDays(6).toString()))
                // today-6 .. today-4: the long-lived VM and the one still to be deleted
                .andExpect(jsonPath("$.points[0].vmCount").value(2))
                .andExpect(jsonPath("$.points[0].vcpu").value(4))
                .andExpect(jsonPath("$.points[0].memoryMb").value(4096))
                .andExpect(jsonPath("$.points[0].diskGb").value(20))
                .andExpect(jsonPath("$.points[2].vmCount").value(2))
                // today-3: the newborn joins before the deletion lands
                .andExpect(jsonPath("$.points[3].day").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.points[3].vmCount").value(3))
                .andExpect(jsonPath("$.points[3].vcpu").value(6))
                // today-2 onwards: the deleted one is gone, the newborn stays
                .andExpect(jsonPath("$.points[4].vmCount").value(2))
                .andExpect(jsonPath("$.points[6].day").value(today.toString()))
                .andExpect(jsonPath("$.points[6].vmCount").value(2))
                // Capacity is today's ACTIVE nodes, not a historical figure.
                .andExpect(jsonPath("$.capacityCpuThreads").isNumber())
                .andExpect(jsonPath("$.capacityMemoryMb").isNumber());
    }

    @Test
    void anEmptyOrgStillGetsOnePointPerDay() throws Exception {
        LocalDate today = LocalDate.now(KST);

        // Named explicitly: an unfiltered org-tier read is platform-wide now,
        // and the point of this case is the org that has nothing in it.
        mockMvc.perform(get("/api/v1/admin/capacity-trend?days=30&orgId=" + org.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.points.length()").value(30))
                .andExpect(jsonPath("$.points[0].vmCount").value(0))
                .andExpect(jsonPath("$.points[29].vmCount").value(0));
    }

    @Test
    void scopingFollowsTheSummaryPanelItSitsBeside() throws Exception {
        long otherOrgId = SeedFixtures.seedOrgId(jdbcTemplate);

        mockMvc.perform(get("/api/v1/admin/capacity-trend")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // An org-tier caller reads any org; the filter narrows rather than refuses.
        mockMvc.perform(get("/api/v1/admin/capacity-trend?orgId=" + pub("orgs", otherOrgId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/capacity-trend?orgId=" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // SYS tier without a drill-in reads the platform-wide series.
        mockMvc.perform(get("/api/v1/admin/capacity-trend")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(90));
    }

    @Test
    void theWindowIsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/admin/capacity-trend?days=1")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/admin/capacity-trend?days=400")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- fixtures ---------------------------------------------------------------

    private static Instant midnight(LocalDate day) {
        return day.atStartOfDay(KST).toInstant();
    }

    private User createUser(String email, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", "추이테스트");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        User saved = userRepository.save(user);
        SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        return saved;
    }

    /** 2 vCPU / 2048 MiB / 10 GiB VM whose lifetime is written explicitly. */
    private void createVm(long orgId, Instant createdAt, Instant deletedAt, String status) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                requesterId, "추이 테스트", imageId, 2, 2048, 10);
        String hostname = "cap-vm-" + UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, created_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?, ?)
                """, nodeId, workspaceId, orgId, requestId, hostname, hostname, imageId, status,
                java.sql.Timestamp.from(createdAt),
                deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
