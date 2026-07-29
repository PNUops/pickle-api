package kr.ac.pusan.pickle.admin;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
 * Dashboard summaries per contract: the org panel counts (requests, decisions,
 * VM statuses, resource guidance, top groups, published services, expiry and
 * attention counters — all scoped to a freshly created org so counts are
 * exact) with the ORG_ADMIN pinning/404-mask, and the SYS_ADMIN system panel
 * shape (nodes/tasks/ip pools; the system-wide counters such as
 * notificationFailureCount are shape-checked only, since they are global and
 * other suites contribute rows).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminSummariesTest {

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
    private long groupBig;
    private long groupSmall;

    @BeforeEach
    void setUp() {
        String slug = "ads-" + UUID.randomUUID().toString().substring(0, 8);
        org = orgRepository.save(new Org("요약 테스트 기관 " + slug, slug, null));
        User orgAdmin = createUser("ads.admin." + slug + "@pusan.ac.kr", UserRole.ORG_ADMIN,
                org.getId());
        User regularUser = createUser("ads.user." + slug + "@pusan.ac.kr", UserRole.USER, null);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        userToken = jwtService.createAccessToken(regularUser);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        groupBig = createGroup();
        groupSmall = createGroup();
    }

    @Test
    void orgSummaryAggregatesAreExactForAFreshOrg() throws Exception {
        LocalDate today = LocalDate.now(KST);
        // 2 VMs in groupBig (one RUNNING expiring in 10d, one NEEDS_ADMIN
        // already expired), 1 STOPPED in groupSmall without endDate
        long vmExpiring = createVm(groupBig, "RUNNING", today.plusDays(10));
        long vmExpired = createVm(groupBig, "NEEDS_ADMIN", today.minusDays(1));
        createVm(groupSmall, "STOPPED", null);
        // request queue: one SUBMITTED, one APPROVED with a fresh review
        createRequest(groupBig, "SUBMITTED");
        long approved = createRequest(groupBig, "APPROVED");
        createReview(approved, "APPROVE");
        // one FAILED task on the expired VM
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 2, 'FAILED', 3)
                """, vmExpired);
        // one published (APPLIED) route on the expiring VM
        long domainId = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, kind, fqdn, root_domain, status, verified_at)
                values (?, 'AUTO', ?, 'pickle.pnuops.com', 'ACTIVE', now())
                returning id
                """, Long.class, vmExpiring,
                "ads-" + UUID.randomUUID().toString().substring(0, 8) + ".pickle.pnuops.com");
        jdbcTemplate.update("""
                insert into routes (domain_id, target_port, protocol, status, generation)
                values (?, 8080, 'HTTP', 'APPLIED', 1)
                """, domainId);

        mockMvc.perform(get("/api/v1/admin/summary")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequestCount").value(1))
                .andExpect(jsonPath("$.recentDecisions14d.approvedCount").value(1))
                .andExpect(jsonPath("$.recentDecisions14d.rejectedCount").value(0))
                .andExpect(jsonPath("$.vmCountsByStatus.RUNNING").value(1))
                .andExpect(jsonPath("$.vmCountsByStatus.STOPPED").value(1))
                .andExpect(jsonPath("$.vmCountsByStatus.NEEDS_ADMIN").value(1))
                .andExpect(jsonPath("$.vmCountsByStatus.DELETED").value(0))
                .andExpect(jsonPath("$.resource.allocatedVcpu").value(6))
                .andExpect(jsonPath("$.resource.allocatedMemoryMb").value(6144))
                .andExpect(jsonPath("$.resource.allocatedDiskGb").value(30))
                .andExpect(jsonPath("$.resource.guidance").isNotEmpty())
                .andExpect(jsonPath("$.topGroupsByVmCount[0].groupId").value(groupBig))
                .andExpect(jsonPath("$.topGroupsByVmCount[0].vmCount").value(2))
                .andExpect(jsonPath("$.topGroupsByVmCount[1].groupId").value(groupSmall))
                .andExpect(jsonPath("$.topGroupsByVmCount[1].vmCount").value(1))
                .andExpect(jsonPath("$.publishedServiceCount").value(1))
                .andExpect(jsonPath("$.expiringVmCount30d").value(1))
                .andExpect(jsonPath("$.attention.failedTaskCount").value(1))
                .andExpect(jsonPath("$.attention.needsAdminVmCount").value(1))
                .andExpect(jsonPath("$.attention.expiredVmCount").value(1));
    }

    @Test
    void orgSummaryScopingFollowsThe404MaskConvention() throws Exception {
        long otherOrgId = SeedFixtures.seedOrgId(jdbcTemplate);

        mockMvc.perform(get("/api/v1/admin/summary")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // ORG_ADMIN: own org implicitly or explicitly, other org → 404
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + org.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + otherOrgId)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // SYS_ADMIN drills into a named org; an unknown org → 404
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + org.getId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/summary?orgId=999999")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound());

        // SYS_ADMIN without orgId → platform-wide aggregate in the same shape
        // (the console home calls it without a drill-in for both roles)
        createPlatformWideProbeVm();
        mockMvc.perform(get("/api/v1/admin/summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequestCount").isNumber())
                .andExpect(jsonPath("$.recentDecisions14d.approvedCount").isNumber())
                .andExpect(jsonPath("$.vmCountsByStatus.RUNNING").isNumber())
                .andExpect(jsonPath("$.resource.allocatedVcpu").isNumber())
                .andExpect(jsonPath("$.resource.guidance").isNotEmpty())
                .andExpect(jsonPath("$.topGroupsByVmCount").isArray())
                .andExpect(jsonPath("$.attention.failedTaskCount").isNumber())
                // spans orgs: the probe VM counts without any org filter
                .andExpect(jsonPath("$.vmCountsByStatus.RUNNING", greaterThanOrEqualTo(1)));
    }

    @Test
    void systemSummaryReturnsThePlatformShapeForSysAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes[?(@.name == 'pve1')].cpuOvercommitRatio").exists())
                .andExpect(jsonPath("$.nodes[?(@.name == 'pve1')].warn").exists())
                .andExpect(jsonPath("$.vmCountsByStatus.RUNNING").isNumber())
                .andExpect(jsonPath("$.tasks.runningCount").isNumber())
                .andExpect(jsonPath("$.tasks.retryingCount").isNumber())
                .andExpect(jsonPath("$.tasks.needsAdminCount").isNumber())
                .andExpect(jsonPath("$.tasks.failed24hCount").isNumber())
                .andExpect(jsonPath("$.notificationFailureCount").isNumber())
                .andExpect(jsonPath("$.certExpiring30dCount").isNumber())
                .andExpect(jsonPath("$.openDriftFindingCount").isNumber())
                .andExpect(jsonPath("$.sshPasswordEnabledVmCount").isNumber())
                .andExpect(jsonPath("$.ipPools[?(@.name == 'guest-private')].allocatedCount")
                        .exists())
                .andExpect(jsonPath("$.ipPools[?(@.name == 'guest-private')].freeCount").exists());
    }

    /** One RUNNING VM in groupBig so the platform-wide summary has this org's data. */
    private void createPlatformWideProbeVm() {
        createVm(groupBig, "RUNNING", null);
    }

    // --- fixtures ---------------------------------------------------------------

    private User createUser(String email, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", "요약테스트");
        user.setRole(role);
        user.setOrgId(orgId);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.save(user);
    }

    private long createGroup() {
        String slug = "ads-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    private long createRequest(long groupId, String status) {
        long templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        return jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb, status)
                values (?, ?, ?, '요약 테스트', ?, 2, 2048, 10,
                        ?::vm_request_status)
                returning id
                """, Long.class, groupId, org.getId(), requesterId, templateId, status);
    }

    private void createReview(long requestId, String decision) {
        long reviewerId = SeedFixtures.sysadminId(jdbcTemplate);
        long templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        // APPROVE rows must carry the granted spec (chk_reviews_approve_granted)
        jdbcTemplate.update("""
                insert into vm_request_reviews (request_id, reviewer_id, decision, granted_vcpu,
                                                granted_memory_mb, granted_disk_gb,
                                                granted_image_id)
                values (?, ?, ?::review_decision, 2, 2048, 10, ?)
                """, requestId, reviewerId, decision, templateId);
    }

    /** 2 vCPU / 2048 MiB / 10 GiB VM with an optional endDate. */
    private long createVm(long groupId, String status, LocalDate endDate) {
        long requestId = createRequest(groupId, "APPROVED");
        long templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        String hostname = "ads-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, groupId, org.getId(), requestId, hostname, hostname,
                templateId, status, endDate);
    }
}
