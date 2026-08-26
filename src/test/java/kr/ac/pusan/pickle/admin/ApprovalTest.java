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
import java.util.UUID;
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
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("승인 타기관").orElseGet(() ->
                orgRepository.save(new Org("승인 타기관", null)));
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
        long workspaceId = createTeam(userToken, "appr-queue-x1");
        long submitted = submit(userToken, workspaceId);
        long canceled = submit(userToken, workspaceId);
        postJson("/api/v1/requests/" + pub("requests", canceled) + "/cancel", userToken, Map.of())
                .andExpect(status().isOk());

        // users have no admin queue → 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/admin/requests").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // own-org admin: no status → ALL statuses (v0.2.3); explicit SUBMITTED filters
        mockMvc.perform(get("/api/v1/admin/requests").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", submitted))).exists())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", canceled))).exists());
        mockMvc.perform(get("/api/v1/admin/requests?status=SUBMITTED")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", submitted))).exists())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", canceled))).doesNotExist());

        // other-org admin sees an empty queue and 404s on the request itself
        mockMvc.perform(get("/api/v1/admin/requests")
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", submitted))).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", submitted))
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", submitted) + "/context")
                        .header("Authorization", "Bearer " + otherOrgAdminToken))
                .andExpect(status().isNotFound());
        postJson("/api/v1/admin/requests/" + pub("requests", submitted) + "/approve", otherOrgAdminToken,
                approveBody())
                .andExpect(status().isNotFound());
        postJson("/api/v1/admin/requests/" + pub("requests", submitted) + "/reject", otherOrgAdminToken,
                Map.of("comment", "타 기관 반려 시도"))
                .andExpect(status().isNotFound());

        // Naming an org outside the caller's own answers 404, the same as every
        // other admin list. It used to pin silently to the caller's org, which
        // said "here is your queue" to a request that asked for another's.
        mockMvc.perform(get("/api/v1/admin/requests?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());

        // SYS_ADMIN sees all orgs and may filter by orgId
        mockMvc.perform(get("/api/v1/admin/requests").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", submitted))).exists());
        mockMvc.perform(get("/api/v1/admin/requests?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("requests", submitted))).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", submitted))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pub("requests", submitted).toString()));

        // An id no organisation has narrows to nothing rather than to everything.
        // The scope renders an empty IN list for this case, which is the one
        // shape no other test reaches — an empty list that rendered as "no
        // clause" would answer with every request instead of none.
        mockMvc.perform(get("/api/v1/admin/requests?orgId=" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void approveWritesReviewCreatesVmAndEnqueuesJob() throws Exception {
        long workspaceId = createTeam(userToken, "appr-approve-x1");
        long requestId = submit(userToken, workspaceId);

        // granted form validation: unusable image / disk below minimum / unknown node → 422
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedImageId", SeedFixtures.UNKNOWN_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.grantedImageId"));
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedDiskGb", 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.grantedDiskGb"));
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.nodeId", SeedFixtures.UNKNOWN_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.nodeId"));

        long jobsBefore = jobRunrJobCount();

        // approve → review embedded in the detail, request APPROVED
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.decision").value("APPROVE"))
                .andExpect(jsonPath("$.review.reviewerName").value("기관 관리자"))
                .andExpect(jsonPath("$.vm.granted.grantedVcpu").value(2))
                .andExpect(jsonPath("$.vm.granted.grantedMemoryMb").value(2048))
                .andExpect(jsonPath("$.vm.granted.nodeId").value((Object) null))
                .andExpect(jsonPath("$.review.decidedAt").isNotEmpty());

        // a CREATING vm row exists with the granted spec and a generated hostname
        List<Vm> vms = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == requestId)
                .toList();
        assertThat(vms).hasSize(1);
        Vm vm = vms.getFirst();
        assertThat(vm.getStatus()).isEqualTo(VmStatus.CREATING);
        // No display name was requested, so the seed identifies the workspace.
        assertThat(vm.getHostname()).matches("vm" + workspaceId + "-[a-z0-9]{4}");
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
                Long.class, pub("requests", requestId).toString());
        assertThat(audits).isEqualTo(1);

        // the requester sees the decision in the user detail view
        mockMvc.perform(get("/api/v1/requests/" + pub("requests", requestId).toString())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.decision").value("APPROVE"));

        // double decisions → 409 REQUEST_ALREADY_DECIDED (approve/reject/cancel)
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/reject", orgAdminToken,
                Map.of("comment", "이미 승인된 신청"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));
        postJson("/api/v1/requests/" + pub("requests", requestId) + "/cancel", userToken, Map.of())
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
            long workspaceId = createTeam(userToken, "appr-guest-account");
            long requestId = submit(userToken, workspaceId);

            postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                    with(approveBody(), "vm.grantedImageId", debian.getPublicId()))
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
        long workspaceId = createTeam(userToken, "appr-slug-x1");
        long requestId = submit(userToken, workspaceId);

        // malformed (bean validation) / reserved word (shared list) → 422 grantedSlug
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedSlug", "-bad-"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.grantedSlug"));
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedSlug", "www"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.grantedSlug"));

        // taken hostname — even of a soft-deleted VM (slugs are never recycled) → 422,
        // and the request stays SUBMITTED (decidable again)
        long otherRequestId = submit(userToken, workspaceId);
        Vm taken = vmRepository.save(new Vm(
                jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class),
                workspaceId, org.getId(), otherRequestId, "appr-slug-taken", "appr-slug-taken",
                image.getId(), image.getSshUsername(), 1, 1024, 10, null, null));
        jdbcTemplate.update(
                "update vms set deleted_at = now(), status = 'DELETED'::vm_status where id = ?",
                taken.getId());
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedSlug", "appr-slug-taken"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.grantedSlug"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "이미 사용 중인 호스트명(슬러그)입니다. 다른 값을 입력하거나 비워서 자동 생성하세요."));
        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", requestId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // granted slug becomes vms.hostname verbatim
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedSlug", "appr-slug-final"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Vm named = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == requestId)
                .findFirst().orElseThrow();
        assertThat(named.getHostname()).isEqualTo("appr-slug-final");

        // blank grantedSlug -> generated from the display name, or the workspace seed when there is none
        postJson("/api/v1/admin/requests/" + pub("requests", otherRequestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.grantedSlug", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Vm auto = vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == otherRequestId && vm.getDeletedAt() == null)
                .findFirst().orElseThrow();
        // No display name was requested, so the seed identifies the workspace.
        assertThat(auto.getHostname()).matches("vm" + workspaceId + "-[a-z0-9]{4}");
    }

    @Test
    void asciiDisplayNameBecomesTheHostnameSeed() throws Exception {
        long workspaceId = createTeam(userToken, "appr-seed-x1");
        long requestId = submit(userToken, workspaceId, org.getId(), "capstone api");

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk());

        Vm vm = vmRepository.findAll().stream()
                .filter(v -> v.getRequestId() == requestId)
                .findFirst().orElseThrow();
        assertThat(vm.getHostname()).matches("capstone-api-[a-z0-9]{4}");
    }

    /**
     * The profanity list matches on substrings, so a seed that trips it would
     * fail every retry — the seed is replaced once instead of burning attempts.
     */
    @Test
    void aDisplayNameTheSlugPolicyRefusesFallsBackInsteadOfFailing() throws Exception {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'profanity_subdomains'",
                "[\"zzsex\"]");
        long workspaceId = createTeam(userToken, "appr-seed-x2");
        long requestId = submit(userToken, workspaceId, org.getId(), "zzsex 프로젝트");

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isOk());

        Vm vm = vmRepository.findAll().stream()
                .filter(v -> v.getRequestId() == requestId)
                .findFirst().orElseThrow();
        assertThat(vm.getHostname()).matches("vm" + workspaceId + "-[a-z0-9]{4}");
    }

    @Test
    void approveSeedsRequestedDisplayNameIntoVmSettings() throws Exception {
        long workspaceId = createTeam(userToken, "appr-dname-x1");
        long requestId = submit(userToken, workspaceId, org.getId(), "학과 세미나 서버");

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
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
                """, Long.class, vm.getPublicId().toString())).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("""
                select detail ->> 'displayName' from audit_logs
                 where action = 'request.approve' and target_id = ?
                """, String.class, pub("requests", requestId).toString().toString())).isEqualTo("학과 세미나 서버");

        // the seeded name surfaces on the VM detail
        mockMvc.perform(get("/api/v1/vms/" + vm.getPublicId())
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
        long workspaceId = createTeam(userToken, "appr-dname-x2");
        // zero-width space + ZWJ + BOM: not blank to Java, empty after sanitizing
        String zeroWidthOnly = new String(new int[] {0x200B, 0x200D, 0xFEFF}, 0, 3);
        long requestId = submit(userToken, workspaceId, org.getId(), zeroWidthOnly);

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
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
                """, String.class, pub("requests", requestId).toString())).isNull();

        // and the VM simply has no display name
        mockMvc.perform(get("/api/v1/vms/" + vm.getPublicId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value((Object) null));
    }

    @Test
    void approveRejectsForcedNodeWithoutTheGrantedImage() throws Exception {
        long workspaceId = createTeam(userToken, "appr-node-x1");
        long requestId = submit(userToken, workspaceId);

        // a second ACTIVE node that hosts none of the seeded OS images
        long emptyNodeId = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, 'https://172.30.0.9:8006', 8, 16384, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, "appr-empty-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        // forcing that node → 422 (the pipeline would clone-fail there otherwise)
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.nodeId", pub("nodes", emptyNodeId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.nodeId"));

        // the request is untouched — forcing the seeded pve1 (hosts the image) approves
        long pve1 = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken,
                with(approveBody(), "vm.nodeId", pub("nodes", pve1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.vm.granted.nodeId").isNotEmpty());

        // drop the extra ACTIVE node: capacity assertions elsewhere sum ACTIVE
        // nodes, and method order differs across environments
        jdbcTemplate.update("delete from nodes where id = ?", emptyNodeId);
    }

    @Test
    void rejectRequiresCommentAndDecidesOnce() throws Exception {
        long workspaceId = createTeam(userToken, "appr-reject-x1");
        long requestId = submit(userToken, workspaceId);

        // comment is mandatory → 422
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/reject", orgAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/reject", orgAdminToken,
                Map.of("comment", "  "))
                .andExpect(status().isUnprocessableContent());

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/reject", orgAdminToken,
                Map.of("comment", "기관 자원 여유 부족으로 반려합니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.review.decision").value("REJECT"))
                .andExpect(jsonPath("$.review.comment").value("기관 자원 여유 부족으로 반려합니다."))
                .andExpect(jsonPath("$.vm.granted").value((Object) null));

        // no vm row is created on rejection
        assertThat(vmRepository.findAll().stream().filter(vm -> vm.getRequestId() == requestId)).isEmpty();

        // rejection is audit-logged; second decision → 409
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.reject' and target_id = ?",
                Long.class, pub("requests", requestId).toString());
        assertThat(audits).isEqualTo(1);
        postJson("/api/v1/admin/requests/" + pub("requests", requestId).toString() + "/reject", orgAdminToken,
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
        Org ctxOrg = orgRepository.findFirstByNameOrderByIdAsc("컨텍스트 기관").orElseGet(() ->
                orgRepository.save(new Org("컨텍스트 기관", null)));
        User ctxAdmin = ensureUser("appr.ctx.admin@pusan.ac.kr", "컨텍스트관리자",
                UserRole.ORG_ADMIN, ctxOrg.getId());
        User ctxUser = ensureUser("appr.ctx.user@pusan.ac.kr", "컨텍스트학생",
                UserRole.USER, null);
        String ctxAdminToken = jwtService.createAccessToken(ctxAdmin);
        String ctxUserToken = jwtService.createAccessToken(ctxUser);

        long workspaceId = createTeam(ctxUserToken, "appr-ctx-x1");
        addMember(ctxUserToken, workspaceId, "appr.other.admin@pusan.ac.kr", "MEMBER");

        // history material: one rejected, one approved (creates an active VM)
        long rejected = submit(ctxUserToken, workspaceId, ctxOrg.getId());
        postJson("/api/v1/admin/requests/" + pub("requests", rejected) + "/reject", ctxAdminToken,
                Map.of("comment", "테스트 반려 사유"))
                .andExpect(status().isOk());
        long approved = submit(ctxUserToken, workspaceId, ctxOrg.getId());
        postJson("/api/v1/admin/requests/" + pub("requests", approved) + "/approve", ctxAdminToken, approveBody())
                .andExpect(status().isOk());

        long current = submit(ctxUserToken, workspaceId, ctxOrg.getId());

        // capacity comes from the seeded ACTIVE node (pve1: 40 threads, 79872 MiB);
        // allocated in this org is exactly the one approved VM (2 vCPU / 2048 MiB / 20 GiB)
        double expectedVcpuRatio = Math.round(2.0 / 40 * 100.0) / 100.0;
        double expectedMemoryRatio = Math.round(2048.0 / 79872 * 100.0) / 100.0;

        mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", current) + "/context")
                        .header("Authorization", "Bearer " + ctxAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.id").value(ctxUser.getPublicId().toString()))
                .andExpect(jsonPath("$.applicant.email").value(ctxUser.getEmail()))
                .andExpect(jsonPath("$.applicant.signupAt").isNotEmpty())
                .andExpect(jsonPath("$.applicant.approvedCount").value(1))
                .andExpect(jsonPath("$.applicant.rejectedCount").value(1))
                .andExpect(jsonPath("$.applicantResources.activeVms[?(@.vcpu == 2)]").exists())
                .andExpect(jsonPath("$.applicantResources.totals.vcpu").value(2))
                .andExpect(jsonPath("$.applicantResources.totals.memoryMb").value(2048))
                .andExpect(jsonPath("$.workspace.id").value(pub("workspaces", workspaceId).toString()))
                .andExpect(jsonPath("$.workspace.kind").value("TEAM"))
                .andExpect(jsonPath("$.workspace.members[?(@.role == 'OWNER')].name")
                        .value(Matchers.hasItem("컨텍스트학생")))
                .andExpect(jsonPath("$.workspace.members.length()").value(2))
                .andExpect(jsonPath("$.workspace.totals.diskGb").value(20))
                .andExpect(jsonPath("$.history[?(@.requestId == \'%s\')].decision".formatted(pub("requests", rejected)))
                        .value(Matchers.hasItem("REJECT")))
                .andExpect(jsonPath("$.history[?(@.requestId == \'%s\')].comment".formatted(pub("requests", rejected)))
                        .value(Matchers.hasItem("테스트 반려 사유")))
                .andExpect(jsonPath("$.history[?(@.requestId == \'%s\')].reviewerName".formatted(pub("requests", rejected)))
                        .value(Matchers.hasItem("컨텍스트관리자")))
                .andExpect(jsonPath("$.history[?(@.requestId == \'%s\')].decision".formatted(pub("requests", approved)))
                        .value(Matchers.hasItem("APPROVE")))
                .andExpect(jsonPath("$.history[?(@.requestId == \'%s\')]".formatted(pub("requests", current))).doesNotExist())
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
            mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", current) + "/context")
                            .header("Authorization", "Bearer " + ctxAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgHeadroom.warnings.length()").value(1))
                    .andExpect(jsonPath("$.guidance").value(ApprovalContextService.GUIDANCE_MEMORY));

            jdbcTemplate.update("update settings set value = '0.01'::jsonb where key = 'vcpu_overcommit_warn'");
            mockMvc.perform(get("/api/v1/admin/requests/" + pub("requests", current) + "/context")
                            .header("Authorization", "Bearer " + ctxAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgHeadroom.warnings.length()").value(2))
                    .andExpect(jsonPath("$.guidance").value(ApprovalContextService.GUIDANCE_BOTH));
        } finally {
            jdbcTemplate.update("update settings set value = '0.8'::jsonb where key = 'memory_usage_warn'");
            jdbcTemplate.update("update settings set value = '3.0'::jsonb where key = 'vcpu_overcommit_warn'");
        }
    }

    /**
     * Deleting a workspace cancels the requests it can see, but a request
     * submitted concurrently with the delete commits after that sweep has run
     * and stays SUBMITTED. Approving it would create a VM inside a workspace
     * nobody can reach, so approval refuses; rejection is the way out, and it
     * still works because it creates nothing.
     */
    @Test
    void approvalRefusesARequestWhoseWorkspaceIsGone() throws Exception {
        long workspaceId = createTeam(userToken, "appr-deleted-ws");
        long requestId = submit(userToken, workspaceId);
        long survivor = submit(userToken, workspaceId);
        jdbcTemplate.update("update workspaces set deleted_at = now(), deleted_by = ? where id = ?",
                regularUser.getId(), workspaceId);

        postJson("/api/v1/admin/requests/" + pub("requests", requestId) + "/approve", orgAdminToken, approveBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_DELETED"));
        assertThat(vmRepository.findAll().stream()
                .filter(vm -> vm.getRequestId() == requestId)).isEmpty();

        postJson("/api/v1/admin/requests/" + pub("requests", survivor) + "/reject", orgAdminToken,
                Map.of("comment", "워크스페이스가 삭제되어 반려합니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    private Map<String, Object> approveBody() {
        Map<String, Object> vm = new HashMap<>();
        vm.put("grantedVcpu", 2);
        vm.put("grantedMemoryMb", 2048);
        vm.put("grantedDiskGb", 20);
        vm.put("grantedImageId", image.getPublicId());
        Map<String, Object> body = new HashMap<>();
        body.put("comment", "요청 사양 그대로 승인합니다.");
        body.put("vm", vm);
        return body;
    }

    /** Dotted keys address the nested per-type member: {@code with(body, "vm.nodeId", 1)}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            body.put(key, value);
            return body;
        }
        ((Map<String, Object>) body.get(key.substring(0, dot))).put(key.substring(dot + 1), value);
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

    private long submit(String token, long workspaceId) throws Exception {
        return submit(token, workspaceId, org.getId());
    }

    private long submit(String token, long workspaceId, long orgId) throws Exception {
        return submit(token, workspaceId, orgId, null);
    }

    private long submit(String token, long workspaceId, long orgId, String displayName) throws Exception {
        Map<String, Object> vm = new HashMap<>();
        vm.put("imageId", image.getPublicId());
        vm.put("flavorId", flavor.getPublicId());
        vm.put("reqVcpu", flavor.getVcpu());
        vm.put("reqMemoryMb", flavor.getMemoryMb());
        vm.put("reqDiskGb", flavor.getDiskGb());
        Map<String, Object> body = new HashMap<>();
        body.put("type", "VM");
        body.put("workspaceId", pub("workspaces", workspaceId));
        body.put("orgId", pub("orgs", orgId));
        body.put("purpose", "승인 흐름 테스트");
        // Every request carries a name. Callers whose subject is the name pass
        // their own; the rest take this one so the body is valid.
        body.put("displayName", displayName != null ? displayName : "승인 테스트 서버");
        body.put("vm", vm);
        String response = postJson("/api/v1/requests", token, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "requests", UUID.fromString(objectMapper.readTree(response).get("id").asString()));
    }

    private long createTeam(String token, String slug) throws Exception {
        String body = postJson("/api/v1/workspaces", token,
                Map.of("kind", "TEAM", "name", "테스트 워크스페이스 " + slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "workspaces", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
    }

    /** Sudo-mode gate: mint the caller's X-Reauth-Token for the protected call. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private void addMember(String token, long workspaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + pub("workspaces", workspaceId) + "/members")
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
