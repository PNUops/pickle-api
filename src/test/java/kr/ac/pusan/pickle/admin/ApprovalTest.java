package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.hamcrest.Matchers;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin approval flow per contract: org scoping (own-org queue, cross-org
 * 404), approve writing review + CREATING vm + enqueued JobRunr job, reject
 * with mandatory comment, double-decision 409, and the ApprovalContext panels
 * including headroom math and the guidance line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ApprovalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private OsImageRepository imageRepository;

    @Autowired
    private VmFlavorRepository flavorRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User regularUser;
    private String userToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String sysAdminToken;
    private Org org;
    private Org otherOrg;
    private OsImage image;
    private VmFlavor flavor;

    @BeforeEach
    void setUp() {
        org = orgRepository.findBySlug(SeedFixtures.ORG_SLUG).orElseThrow();
        otherOrg = orgRepository.findBySlug("appr-other").orElseGet(() ->
                orgRepository.save(new Org("다른 기관", "appr-other", null)));
        regularUser = ensureUser("appr.user@pusan.ac.kr", "승인학생", UserRole.USER, null);
        User orgAdmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        User otherOrgAdmin = ensureUser("appr.other.admin@pusan.ac.kr", "타기관관리자",
                UserRole.ORG_ADMIN, otherOrg.getId());
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        userToken = jwtService.createAccessToken(regularUser);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        image = imageRepository.findAll().stream()
                .filter(t -> t.getName().equals("ubuntu-24.04") && t.getStatus() == CatalogStatus.ACTIVE)
                .findFirst().orElseThrow();
        flavor = flavorRepository.findAll().stream()
                .filter(f -> f.getName().equals("basic"))
                .findFirst().orElseThrow();
    }

    @Test
    void queueIsOrgScopedAndStatusFilterable() throws Exception {
        long groupId = createTeam(userToken, "appr-queue-x1");
        long submitted = submit(userToken, groupId);
        long canceled = submit(userToken, groupId);
        postJson("/api/v1/vm-requests/" + canceled + "/cancel", userToken, Map.of())
                .andExpect(status().isOk());

        // users have no admin queue → 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/admin/vm-requests").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // own-org admin: no status → ALL statuses (v0.2.3); explicit SUBMITTED filters
        mockMvc.perform(get("/api/v1/admin/vm-requests").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).exists())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(canceled)).exists());
        mockMvc.perform(get("/api/v1/admin/vm-requests?status=SUBMITTED")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).exists())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(canceled)).doesNotExist());

        // other-org admin sees an empty queue and 404s on the request itself
        mockMvc.perform(get("/api/v1/admin/vm-requests")
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + submitted)
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + submitted + "/context")
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound());
        postJson("/api/v1/admin/vm-requests/" + submitted + "/approve", otherOrgAdminToken,
                approveBody())
                .andExpect(status().isNotFound());
        postJson("/api/v1/admin/vm-requests/" + submitted + "/reject", otherOrgAdminToken,
                Map.of("comment", "타 기관 반려 시도"))
                .andExpect(status().isNotFound());

        // ORG_ADMIN cannot escape their org via the orgId filter (pinned)
        mockMvc.perform(get("/api/v1/admin/vm-requests?orgId=" + otherOrg.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).exists());

        // SYS_ADMIN sees all orgs and may filter by orgId
        mockMvc.perform(get("/api/v1/admin/vm-requests").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).exists());
        mockMvc.perform(get("/api/v1/admin/vm-requests?orgId=" + otherOrg.getId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(submitted)).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + submitted)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submitted));
    }

    @Test
    void approveWritesReviewCreatesVmAndEnqueuesJob() throws Exception {
        long groupId = createTeam(userToken, "appr-approve-x1");
        long requestId = submit(userToken, groupId);

        // granted form validation: unusable image / disk below minimum / unknown node → 422
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedImageId", 999_999))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("grantedImageId"));
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedDiskGb", 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("grantedDiskGb"));
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "nodeId", 999_999))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));

        long jobsBefore = jobRunrJobCount();

        // approve → review embedded in the detail, request APPROVED
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.decision").value("APPROVE"))
                .andExpect(jsonPath("$.review.reviewerName").value("기관 관리자"))
                .andExpect(jsonPath("$.review.grantedVcpu").value(2))
                .andExpect(jsonPath("$.review.grantedMemoryMb").value(2048))
                .andExpect(jsonPath("$.review.nodeId").value((Object) null))
                .andExpect(jsonPath("$.review.decidedAt").isNotEmpty());

        // a CREATING vm row exists with the granted spec and a slug-prefixed hostname
        List<Vm> vms = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == requestId)
                .toList();
        assertThat(vms).hasSize(1);
        Vm vm = vms.getFirst();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.CREATING);
        assertThat(vm.getHostname()).startsWith("appr-approve-x1-");
        assertThat(vm.getVcpu()).isEqualTo(2);
        assertThat(vm.getMemoryMb()).isEqualTo(2048);
        assertThat(vm.getDiskGb()).isEqualTo(20);
        assertThat(vm.getSshUsername()).isEqualTo("ubuntu");
        assertThat(vm.getProxmoxVmid()).isNull();
        assertThat(vm.getOrgId()).isEqualTo(org.getId());

        // a mock-provisioning job was enqueued through the ProvisioningService
        // seam; the enqueue runs afterCommit (completed by the time MockMvc
        // returns) and the earlier failed (422) approves enqueued nothing
        assertThat(jobRunrJobCount()).isEqualTo(jobsBefore + 1);

        // approval is audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.approve' and target_id = ?",
                Long.class, requestId);
        assertThat(audits).isEqualTo(1);

        // the requester sees the decision in the user detail view
        mockMvc.perform(get("/api/v1/vm-requests/" + requestId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.decision").value("APPROVE"));

        // double decisions → 409 REQUEST_ALREADY_DECIDED (approve/reject/cancel)
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));
        postJson("/api/v1/admin/vm-requests/" + requestId + "/reject", orgAdminToken,
                Map.of("comment", "이미 승인된 신청"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));
        postJson("/api/v1/vm-requests/" + requestId + "/cancel", userToken, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));

        // the rejected double decisions rolled back without enqueuing anything
        assertThat(jobRunrJobCount()).isEqualTo(jobsBefore + 1);
    }

    @Test
    void vmTakesItsGuestAccountFromTheGrantedImage() throws Exception {
        // Each distribution ships its own admin account, so the VM must carry the
        // granted image's account rather than the platform's historical 'ubuntu'.
        OsImage debian = imageRepository.save(new OsImage("appr-debian-13", "Debian 13",
                "debian", "13", "debian", 1005, image.getNodeId(), 1, 10,
                CatalogStatus.ACTIVE, null));
        try {
            long groupId = createTeam(userToken, "appr-guest-account");
            long requestId = submit(userToken, groupId);

            postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                    with(approveBody(), "grantedImageId", debian.getId()))
                    .andExpect(status().isOk());

            Vm vm = vmRepository.findAll().stream()
                    .filter(candidate -> candidate.getRequestId() == requestId)
                    .findFirst().orElseThrow();
            assertThat(vm.getImageId()).isEqualTo(debian.getId());
            assertThat(vm.getSshUsername()).isEqualTo("debian");
        } finally {
            // Retire the extra catalog row: the wizard list is shared state
            // across the classes on this context. The VM keeps its reference.
            debian.setStatus(CatalogStatus.DISABLED);
            imageRepository.saveAndFlush(debian);
        }
    }

    @Test
    void grantedSlugFinalizesHostnameAndBlankFallsBackToAuto() throws Exception {
        long groupId = createTeam(userToken, "appr-slug-x1");
        long requestId = submit(userToken, groupId);

        // malformed (bean validation) / reserved word (shared list) → 422 grantedSlug
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedSlug", "-bad-"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("grantedSlug"));
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedSlug", "www"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("grantedSlug"));

        // taken hostname — even of a soft-deleted VM (slugs are never recycled) → 422,
        // and the request stays SUBMITTED (decidable again)
        long otherRequestId = submit(userToken, groupId);
        Vm taken = vmRepository.save(new Vm(
                jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class),
                groupId, org.getId(), otherRequestId, "appr-slug-taken", "appr-slug-taken",
                image.getId(), image.getSshUsername(), 1, 1024, 10, null, null));
        jdbcTemplate.update(
                "update vms set deleted_at = now(), status = 'DELETED'::vm_status where id = ?",
                taken.getId());
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedSlug", "appr-slug-taken"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("grantedSlug"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "이미 사용 중인 호스트명(슬러그)입니다. 다른 값을 입력하거나 비워서 자동 생성하세요."));
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + requestId)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // granted slug becomes vms.hostname verbatim
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedSlug", "appr-slug-final"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Vm named = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == requestId)
                .findFirst().orElseThrow();
        assertThat(named.getHostname()).isEqualTo("appr-slug-final");

        // blank grantedSlug → today's auto generation (group slug + 4-char suffix)
        postJson("/api/v1/admin/vm-requests/" + otherRequestId + "/approve", orgAdminToken,
                with(approveBody(), "grantedSlug", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Vm auto = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == otherRequestId && vm.getDeletedAt() == null)
                .findFirst().orElseThrow();
        assertThat(auto.getHostname()).matches("appr-slug-x1-[a-z0-9]{4}");
    }

    @Test
    void approveSeedsRequestedDisplayNameIntoVmSettings() throws Exception {
        long groupId = createTeam(userToken, "appr-dname-x1");
        long requestId = submit(userToken, groupId, org.getId(), "학과 세미나 서버");

        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.displayName").value("학과 세미나 서버"));

        Vm vm = vmRepository.findAll().stream()
                .filter(v -> v.getRequestId() == requestId)
                .findFirst().orElseThrow();
        // The vm_settings row is seeded with the requester as the writer.
        assertThat(jdbcTemplate.queryForObject("""
                select value #>> '{}' from vm_settings where vm_id = ? and key = 'display_name'
                """, String.class, vm.getId())).isEqualTo("학과 세미나 서버");
        assertThat(jdbcTemplate.queryForObject("""
                select updated_by from vm_settings where vm_id = ? and key = 'display_name'
                """, Long.class, vm.getId())).isEqualTo(regularUser.getId());
        // The seeding has no vm.setting_update row of its own — request.approve
        // carries the provenance instead.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'vm.setting_update' and target_id = ?
                """, Long.class, vm.getId())).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("""
                select detail ->> 'displayName' from audit_logs
                 where action = 'request.approve' and target_id = ?
                """, String.class, requestId)).isEqualTo("학과 세미나 서버");

        // the seeded name surfaces on the VM detail
        mockMvc.perform(get("/api/v1/vms/" + vm.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("학과 세미나 서버"));
    }

    /**
     * The seeder strips control/format characters, so a name made of nothing
     * else stores nothing — and the approval audit must say so. Auditing the
     * raw request value would claim a display name that no vm_settings row
     * ever carried.
     */
    @Test
    void approveAuditsOnlyTheDisplayNameItActuallyStored() throws Exception {
        long groupId = createTeam(userToken, "appr-dname-x2");
        // zero-width space + ZWJ + BOM: not blank to Java, empty after sanitizing
        String zeroWidthOnly = new String(new int[] {0x200B, 0x200D, 0xFEFF}, 0, 3);
        long requestId = submit(userToken, groupId, org.getId(), zeroWidthOnly);

        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        Vm vm = vmRepository.findAll().stream()
                .filter(v -> v.getRequestId() == requestId)
                .findFirst().orElseThrow();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from vm_settings where vm_id = ? and key = 'display_name'
                """, Long.class, vm.getId())).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("""
                select detail ->> 'displayName' from audit_logs
                 where action = 'request.approve' and target_id = ?
                """, String.class, requestId)).isNull();

        // and the VM simply has no display name
        mockMvc.perform(get("/api/v1/vms/" + vm.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value((Object) null));
    }

    @Test
    void approveRejectsForcedNodeWithoutTheGrantedImage() throws Exception {
        long groupId = createTeam(userToken, "appr-node-x1");
        long requestId = submit(userToken, groupId);

        // a second ACTIVE node that hosts none of the seeded OS images
        long emptyNodeId = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, 'https://172.30.0.9:8006', 8, 16384, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, "appr-empty-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        // forcing that node → 422 (the pipeline would clone-fail there otherwise)
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "nodeId", emptyNodeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));

        // the request is untouched — forcing the seeded pve1 (hosts the image) approves
        long pve1 = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        postJson("/api/v1/admin/vm-requests/" + requestId + "/approve", orgAdminToken,
                with(approveBody(), "nodeId", pve1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.nodeId").isNumber());

        // drop the extra ACTIVE node: capacity assertions elsewhere sum ACTIVE
        // nodes, and method order differs across environments
        jdbcTemplate.update("delete from nodes where id = ?", emptyNodeId);
    }

    @Test
    void rejectRequiresCommentAndDecidesOnce() throws Exception {
        long groupId = createTeam(userToken, "appr-reject-x1");
        long requestId = submit(userToken, groupId);

        // comment is mandatory → 422
        postJson("/api/v1/admin/vm-requests/" + requestId + "/reject", orgAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/admin/vm-requests/" + requestId + "/reject", orgAdminToken,
                Map.of("comment", "  "))
                .andExpect(status().isUnprocessableContent());

        postJson("/api/v1/admin/vm-requests/" + requestId + "/reject", orgAdminToken,
                Map.of("comment", "기관 자원 여유 부족으로 반려합니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.review.decision").value("REJECT"))
                .andExpect(jsonPath("$.review.comment").value("기관 자원 여유 부족으로 반려합니다."))
                .andExpect(jsonPath("$.review.grantedVcpu").value((Object) null));

        // no vm row is created on rejection
        assertThat(vmRepository.findAll().stream().filter(vm -> vm.getRequestId() == requestId)).isEmpty();

        // rejection is audit-logged; second decision → 409
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.reject' and target_id = ?",
                Long.class, requestId);
        assertThat(audits).isEqualTo(1);
        postJson("/api/v1/admin/vm-requests/" + requestId + "/reject", orgAdminToken,
                Map.of("comment", "중복 반려"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));
    }

    @Test
    void approvalContextShowsPanelsHeadroomAndGuidance() throws Exception {
        // Capacity sums every ACTIVE node, and earlier test classes sharing
        // this database leave ACTIVE fixture nodes behind in some execution
        // orders — demote everything but the seeded pve1 before asserting.
        jdbcTemplate.update(
                "update nodes set status = 'MAINTENANCE' where name <> 'pve1'");

        // Dedicated org + applicant so counts/totals are isolated from the
        // other tests sharing this context's database.
        Org ctxOrg = orgRepository.findBySlug("appr-ctx").orElseGet(() ->
                orgRepository.save(new Org("컨텍스트 기관", "appr-ctx", null)));
        User ctxAdmin = ensureUser("appr.ctx.admin@pusan.ac.kr", "컨텍스트관리자",
                UserRole.ORG_ADMIN, ctxOrg.getId());
        User ctxUser = ensureUser("appr.ctx.user@pusan.ac.kr", "컨텍스트학생",
                UserRole.USER, null);
        String ctxAdminToken = jwtService.createAccessToken(ctxAdmin);
        String ctxUserToken = jwtService.createAccessToken(ctxUser);

        long groupId = createTeam(ctxUserToken, "appr-ctx-x1");
        addMember(ctxUserToken, groupId, "appr.other.admin@pusan.ac.kr", "MEMBER");

        // history material: one rejected, one approved (creates an active VM)
        long rejected = submit(ctxUserToken, groupId, ctxOrg.getId());
        postJson("/api/v1/admin/vm-requests/" + rejected + "/reject", ctxAdminToken,
                Map.of("comment", "테스트 반려 사유"))
                .andExpect(status().isOk());
        long approved = submit(ctxUserToken, groupId, ctxOrg.getId());
        postJson("/api/v1/admin/vm-requests/" + approved + "/approve", ctxAdminToken, approveBody())
                .andExpect(status().isOk());

        long current = submit(ctxUserToken, groupId, ctxOrg.getId());

        // capacity comes from the seeded ACTIVE node (pve1: 40 threads, 79872 MiB);
        // allocated in this org is exactly the one approved VM (2 vCPU / 2048 MiB / 20 GiB)
        double expectedVcpuRatio = Math.round(2.0 / 40 * 100.0) / 100.0;
        double expectedMemoryRatio = Math.round(2048.0 / 79872 * 100.0) / 100.0;

        mockMvc.perform(get("/api/v1/admin/vm-requests/" + current + "/context")
                        .header("Authorization", "Bearer " + ctxAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.id").value(ctxUser.getId()))
                .andExpect(jsonPath("$.applicant.email").value(ctxUser.getEmail()))
                .andExpect(jsonPath("$.applicant.signupAt").isNotEmpty())
                .andExpect(jsonPath("$.applicant.approvedCount").value(1))
                .andExpect(jsonPath("$.applicant.rejectedCount").value(1))
                .andExpect(jsonPath("$.applicantResources.activeVms[?(@.vcpu == 2)]").exists())
                .andExpect(jsonPath("$.applicantResources.totals.vcpu").value(2))
                .andExpect(jsonPath("$.applicantResources.totals.memoryMb").value(2048))
                .andExpect(jsonPath("$.group.id").value(groupId))
                .andExpect(jsonPath("$.group.kind").value("TEAM"))
                .andExpect(jsonPath("$.group.members[?(@.role == 'OWNER')].name")
                        .value(Matchers.hasItem("컨텍스트학생")))
                .andExpect(jsonPath("$.group.members.length()").value(2))
                .andExpect(jsonPath("$.group.totals.diskGb").value(20))
                .andExpect(jsonPath("$.history[?(@.requestId == %d)].decision".formatted(rejected))
                        .value(Matchers.hasItem("REJECT")))
                .andExpect(jsonPath("$.history[?(@.requestId == %d)].comment".formatted(rejected))
                        .value(Matchers.hasItem("테스트 반려 사유")))
                .andExpect(jsonPath("$.history[?(@.requestId == %d)].reviewerName".formatted(rejected))
                        .value(Matchers.hasItem("컨텍스트관리자")))
                .andExpect(jsonPath("$.history[?(@.requestId == %d)].decision".formatted(approved))
                        .value(Matchers.hasItem("APPROVE")))
                .andExpect(jsonPath("$.history[?(@.requestId == %d)]".formatted(current)).doesNotExist())
                .andExpect(jsonPath("$.orgHeadroom.allocated.vcpu").value(2))
                .andExpect(jsonPath("$.orgHeadroom.allocated.memoryMb").value(2048))
                .andExpect(jsonPath("$.orgHeadroom.allocated.diskGb").value(20))
                .andExpect(jsonPath("$.orgHeadroom.capacity.cpuThreads").value(40))
                .andExpect(jsonPath("$.orgHeadroom.capacity.memoryMb").value(79872))
                .andExpect(jsonPath("$.orgHeadroom.vcpuOvercommitRatio").value(expectedVcpuRatio))
                .andExpect(jsonPath("$.orgHeadroom.memoryUsageRatio").value(expectedMemoryRatio))
                .andExpect(jsonPath("$.orgHeadroom.warnings").isEmpty())
                .andExpect(jsonPath("$.guidance").value(ApprovalContextService.GUIDANCE_AMPLE));

        // lowering the settings thresholds flips warnings + guidance
        try {
            jdbcTemplate.update("update settings set value = '0.01'::jsonb where key = 'memory_usage_warn'");
            mockMvc.perform(get("/api/v1/admin/vm-requests/" + current + "/context")
                            .header("Authorization", "Bearer " + ctxAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgHeadroom.warnings.length()").value(1))
                    .andExpect(jsonPath("$.guidance").value(ApprovalContextService.GUIDANCE_MEMORY));

            jdbcTemplate.update("update settings set value = '0.01'::jsonb where key = 'vcpu_overcommit_warn'");
            mockMvc.perform(get("/api/v1/admin/vm-requests/" + current + "/context")
                            .header("Authorization", "Bearer " + ctxAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgHeadroom.warnings.length()").value(2))
                    .andExpect(jsonPath("$.guidance").value(ApprovalContextService.GUIDANCE_BOTH));
        } finally {
            jdbcTemplate.update("update settings set value = '0.8'::jsonb where key = 'memory_usage_warn'");
            jdbcTemplate.update("update settings set value = '3.0'::jsonb where key = 'vcpu_overcommit_warn'");
        }
    }

    private Map<String, Object> approveBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("grantedVcpu", 2);
        body.put("grantedMemoryMb", 2048);
        body.put("grantedDiskGb", 20);
        body.put("grantedImageId", image.getId());
        body.put("comment", "요청 사양 그대로 승인합니다.");
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private long jobRunrJobCount() {
        // JobRunr stores the runtime class of the captured bean, e.g.
        // "kr.ac.pusan.pickle.provisioning.MockProvisionVmJob.provisionVm(long)".
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%.provisionVm(%'",
                Long.class);
        return count != null ? count : 0;
    }

    private long submit(String token, long groupId) throws Exception {
        return submit(token, groupId, org.getId());
    }

    private long submit(String token, long groupId, long orgId) throws Exception {
        return submit(token, groupId, orgId, null);
    }

    private long submit(String token, long groupId, long orgId, String displayName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        body.put("groupId", groupId);
        body.put("orgId", orgId);
        body.put("imageId", image.getId());
        body.put("flavorId", flavor.getId());
        body.put("purpose", "승인 흐름 테스트");
        body.put("reqVcpu", flavor.getVcpu());
        body.put("reqMemoryMb", flavor.getMemoryMb());
        body.put("reqDiskGb", flavor.getDiskGb());
        String response = postJson("/api/v1/vm-requests", token, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createTeam(String token, String slug) throws Exception {
        String body = postJson("/api/v1/groups", token,
                Map.of("kind", "TEAM", "name", "테스트 그룹 " + slug, "slug", slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Sudo-mode gate: mint the caller's X-Reauth-Token for the protected call. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private void addMember(String token, long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .header(ReauthTestSupport.HEADER, reauth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
