package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
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
 * VM statuses, resource guidance, top workspaces, published services, expiry and
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
    private long workspaceBig;
    private long workspaceSmall;

    @BeforeEach
    void setUp() {
        String slug = "ads-" + UUID.randomUUID().toString().substring(0, 8);
        org = orgRepository.save(new Org("요약 테스트 기관 " + slug, null));
        User orgAdmin = createUser("ads.admin." + slug + "@pusan.ac.kr", UserRole.ORG_ADMIN,
                org.getId());
        User regularUser = createUser("ads.user." + slug + "@pusan.ac.kr", UserRole.USER, null);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        userToken = jwtService.createAccessToken(regularUser);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        workspaceBig = createWorkspace();
        workspaceSmall = createWorkspace();
    }

    @Test
    void orgSummaryAggregatesAreExactForAFreshOrg() throws Exception {
        LocalDate today = LocalDate.now(KST);
        // 2 VMs in workspaceBig (one RUNNING expiring in 10d, one NEEDS_ADMIN
        // already expired), 1 STOPPED in workspaceSmall without endDate
        long vmExpiring = createVm(workspaceBig, "RUNNING", today.plusDays(10));
        long vmExpired = createVm(workspaceBig, "NEEDS_ADMIN", today.minusDays(1));
        createVm(workspaceSmall, "STOPPED", null);
        // request queue: one SUBMITTED, one APPROVED with a fresh review
        createRequest(workspaceBig, "SUBMITTED");
        long approved = createRequest(workspaceBig, "APPROVED");
        createReview(approved, "APPROVE");
        // one FAILED task on the expired VM
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 2, 'FAILED', 3)
                """, vmExpired);
        // one published (APPLIED) route on the expiring VM
        long domainId = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, workspace_id, org_id, kind, fqdn, root_domain,
                                     status, verified_at)
                select v.id, v.workspace_id, v.org_id, 'AUTO', ?, 'pusan.dev', 'ACTIVE', now()
                  from vms v where v.id = ?
                returning id
                """, Long.class,
                "ads-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev", vmExpiring);
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
                .andExpect(jsonPath("$.topWorkspacesByVmCount[0].workspaceId").value(pub("workspaces", workspaceBig).toString()))
                .andExpect(jsonPath("$.topWorkspacesByVmCount[0].vmCount").value(2))
                .andExpect(jsonPath("$.topWorkspacesByVmCount[1].workspaceId").value(pub("workspaces", workspaceSmall).toString()))
                .andExpect(jsonPath("$.topWorkspacesByVmCount[1].vmCount").value(1))
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
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + org.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + pub("orgs", otherOrgId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // SYS_ADMIN drills into a named org; an unknown org → 404
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + org.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/summary?orgId=" + SeedFixtures.UNKNOWN_ID)
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
                .andExpect(jsonPath("$.topWorkspacesByVmCount").isArray())
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
                .andExpect(jsonPath("$.ipPools[?(@.name == 'guest-private')].freeCount").exists())
                // One live tile per node, whatever the hypervisor answers.
                .andExpect(jsonPath("$.nodesLive").isArray())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").exists());
    }

    /** One RUNNING VM in workspaceBig so the platform-wide summary has this org's data. */
    private void createPlatformWideProbeVm() {
        createVm(workspaceBig, "RUNNING", null);
    }

    // --- fixtures ---------------------------------------------------------------

    @Test
    void selectedDashboardFieldsKeepEachInstitutionAndWorkspaceInsideTheirOwnSource() throws Exception {
        createVm(workspaceBig, "RUNNING", null); createVm(workspaceSmall, "RUNNING", null);
        User actor = createUser("summary.selected." + UUID.randomUUID() + "@pusan.ac.kr", UserRole.USER, null);
        Org other = orgRepository.save(new Org("요약 관측 다른 기관 " + UUID.randomUUID(), null));
        assign(actor, org.getId(), java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_READ),
                kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.listed(java.util.Set.of(pub("workspaces", workspaceBig))));
        assign(actor, other.getId(), java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_OBSERVE),
                kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.listed(java.util.Set.of(pub("workspaces", workspaceSmall))));
        String token = jwtService.createAccessToken(userRepository.findById(actor.getId()).orElseThrow());
        mockMvc.perform(get("/api/v1/admin/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vmCountsByStatus.RUNNING").value(1))
                .andExpect(jsonPath("$.topWorkspacesByVmCount.length()").value(1))
                .andExpect(jsonPath("$.resource.allocatedVcpu").value(0))
                .andExpect(jsonPath("$.pendingRequestCount").doesNotExist())
                .andExpect(jsonPath("$.publishedServiceCount").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/summary").param("orgId",org.getPublicId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resource").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/capacity-trend").param("orgId",org.getPublicId().toString())
                        .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void requestCountsRequireTheExactResourceKindAndWorkspaceBeforeAggregation() throws Exception {
        createRequest(workspaceBig,"SUBMITTED"); createRequest(workspaceSmall,"SUBMITTED");
        RequestFixtures.insertLlmKeyRequest(jdbcTemplate, workspaceBig, org.getId(), SeedFixtures.orgadminId(jdbcTemplate), "다른 종류 신청");
        User actor = createUser("summary.request." + UUID.randomUUID() + "@pusan.ac.kr", UserRole.USER, null);
        assign(actor, org.getId(), java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_REQUEST_READ),
                kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.listed(java.util.Set.of(pub("workspaces",workspaceBig))));
        mockMvc.perform(get("/api/v1/admin/summary").header("Authorization", "Bearer "
                        + jwtService.createAccessToken(userRepository.findById(actor.getId()).orElseThrow())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingRequestCount").value(1))
                .andExpect(jsonPath("$.vmCountsByStatus").doesNotExist())
                .andExpect(jsonPath("$.resource").doesNotExist()).andExpect(jsonPath("$.attention").doesNotExist());
    }

    @Test
    void emptyListedAndNoticeOnlyAuthorityDoNotBecomeAnUnrestrictedDashboard() throws Exception {
        User actor = createUser("summary.empty." + UUID.randomUUID() + "@pusan.ac.kr", UserRole.USER, null);
        assign(actor, org.getId(), java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_OBSERVE),
                kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.listed(java.util.Set.of()));
        String token = jwtService.createAccessToken(userRepository.findById(actor.getId()).orElseThrow());
        mockMvc.perform(get("/api/v1/admin/summary").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/capacity-trend").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        var assignment = kr.ac.pusan.pickle.security.institution.InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(),
                java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.NOTICE_MANAGE), java.util.Map.of(), 0);
        replaceAssignment(actor, org.getId(), assignment);
        mockMvc.perform(get("/api/v1/admin/summary").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notices").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void allAllocationScopeIncludesAWorkspaceAndResourceCreatedAfterTheGrant() throws Exception {
        User actor = createUser("summary.all." + UUID.randomUUID() + "@pusan.ac.kr", UserRole.USER, null);
        assign(actor, org.getId(), java.util.Set.of(kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_OBSERVE),
                kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope.all());
        long future = createWorkspace(); createVm(future,"RUNNING",null);
        mockMvc.perform(get("/api/v1/admin/capacity-trend").param("days","7").header("Authorization", "Bearer "
                        + jwtService.createAccessToken(userRepository.findById(actor.getId()).orElseThrow())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points[6].vmCount").value(1))
                .andExpect(jsonPath("$.points[6].vcpu").value(2));
    }

    private void assign(User actor,long institution,java.util.Set<kr.ac.pusan.pickle.security.institution.InstitutionWork> work,
            kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope scope) {
        var assignment=kr.ac.pusan.pickle.security.institution.InstitutionAssignment.selected(actor.getPublicId(),pub("orgs",institution),work,
                java.util.Map.of(kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup.VM,scope),0);
        replaceAssignment(actor,institution,assignment);
    }

    private void replaceAssignment(User actor,long institution,kr.ac.pusan.pickle.security.institution.InstitutionAssignment assignment) {
        var encoded=new kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec().encode(assignment);
        jdbcTemplate.update("""
                insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)
                values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))
                on conflict(user_id,org_id) do update set role=excluded.role,permission_mode=excluded.permission_mode,
                permission_payload=excluded.permission_payload,permission_revision=excluded.permission_revision
                """,actor.getId(),institution,assignment.displayRole().name(),encoded.mode(),encoded.payload());
        jdbcTemplate.update("update users set role=?::user_role where id=?",assignment.displayRole().name(),actor.getId());
    }

    private User createUser(String email, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", "요약테스트");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        User saved = userRepository.save(user);
        SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        return saved;
    }

    private long createWorkspace() {
        String slug = "ads-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('PROJECT', ?) returning id",
                Long.class, slug);
    }

    private long createRequest(long workspaceId, String status) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        return RequestFixtures.insertVmRequestWithStatus(jdbcTemplate, workspaceId, org.getId(),
                requesterId, "요약 테스트", imageId, status);
    }

    private void createReview(long requestId, String decision) {
        long reviewerId = SeedFixtures.sysadminId(jdbcTemplate);
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        // APPROVE rows carry the granted spec, which now lives on the detail row
        if ("APPROVE".equals(decision)) {
            RequestFixtures.approveVmRequest(jdbcTemplate, requestId, reviewerId, imageId, 2, 2048, 10);
        } else {
            jdbcTemplate.update("""
                    insert into request_reviews (request_id, reviewer_id, decision, comment)
                    values (?, ?, ?::review_decision, '반려')
                    """, requestId, reviewerId, decision);
        }
    }

    /** 2 vCPU / 2048 MiB / 10 GiB VM with an optional endDate. */
    private long createVm(long workspaceId, String status, LocalDate endDate) {
        long requestId = createRequest(workspaceId, "APPROVED");
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        String hostname = "ads-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, workspaceId, org.getId(), requestId, hostname, hostname,
                imageId, status, endDate);
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
