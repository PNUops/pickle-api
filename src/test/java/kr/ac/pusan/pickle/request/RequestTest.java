package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.NodeRepository;
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
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * User VM request flow per contract: creation validation matrix (workspace role,
 * the OS and spec-preset axes incl. a catalog with no ACTIVE row, spec-reason
 * rule, subdomain/domain rules), list/detail
 * visibility incl. the 403 non-member workspaceId filter, and cancel permissions
 * with the 409 double-decision guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RequestTest {

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
    private NodeRepository nodeRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User requester;
    private User otherMember;
    private User member;
    private User outsider;
    private String requesterToken;
    private String otherMemberToken;
    private String memberToken;
    private String outsiderToken;
    private Org org;
    private OsImage image;
    private VmFlavor basicFlavor;
    private VmFlavor smallFlavor;

    @BeforeEach
    void setUp() {
        requester = ensureUser("vmr.requester@pusan.ac.kr", "신청자");
        otherMember = ensureUser("vmr.other@pusan.ac.kr", "다른 구성원");
        member = ensureUser("vmr.member@pusan.ac.kr", "구성원");
        outsider = ensureUser("vmr.outsider@pusan.ac.kr", "외부인");
        requesterToken = jwtService.createAccessToken(requester);
        otherMemberToken = jwtService.createAccessToken(otherMember);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
        org = orgRepository.findBySlug(SeedFixtures.ORG_SLUG).orElseThrow();
        image = imageRepository.findAll().stream()
                .filter(t -> t.getName().equals("ubuntu-24.04") && t.getStatus() == CatalogStatus.ACTIVE)
                .findFirst().orElseThrow();
        basicFlavor = flavorByName("basic");
        smallFlavor = flavorByName("small");
    }

    private VmFlavor flavorByName(String name) {
        return flavorRepository.findAll().stream()
                .filter(f -> f.getName().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void createValidatesRoleImageSpecAndDomains() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-create-x1");
        addMember(requesterToken, workspaceId, member.getEmail(), "MEMBER");

        // OWNER submits with the chosen preset's specs → 201 SUBMITTED, review null
        postJson("/api/v1/requests", requesterToken, validBody(workspaceId))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.review").value((Object) null))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.workspaceName").isNotEmpty())
                .andExpect(jsonPath("$.orgName").isNotEmpty())
                .andExpect(jsonPath("$.requesterId").value(requester.getId()))
                .andExpect(jsonPath("$.requesterName").value("신청자"))
                .andExpect(jsonPath("$.vm.imageId").value(image.getId()))
                .andExpect(jsonPath("$.vm.flavorId").value(basicFlavor.getId()));

        // any member may ask — what a request costs is decided at approval,
        // and reaching the resulting VM is the access list's business
        postJson("/api/v1/requests", memberToken, validBody(workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requesterId").value(member.getId()));

        // a non-member still cannot submit → 403 WORKSPACE_ROLE_INSUFFICIENT
        postJson("/api/v1/requests", outsiderToken, validBody(workspaceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ROLE_INSUFFICIENT"));

        // unknown workspace / org / image → 404
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "workspaceId", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "orgId", 999_999))
                .andExpect(status().isNotFound());
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.imageId", 999_999))
                .andExpect(status().isNotFound());
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.flavorId", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("해당 사양 프리셋이 존재하지 않습니다."));

        // DISABLED image → 422
        OsImage disabled = imageRepository.save(new OsImage("vmr-disabled", "비활성 OS 이미지",
                "ubuntu", "24.04", "ubuntu", 1002,
                nodeRepository.findAll().getFirst().getId(), 1, 10,
                CatalogStatus.DISABLED, null));
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "vm.imageId", disabled.getId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.imageId"));

        // below the OS image's minimum disk → 422, whatever the preset offers
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.reqDiskGb", 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.reqDiskGb"))
                .andExpect(jsonPath("$.errors[0].message").value("이 OS의 최소 디스크 크기는 10GiB입니다."));

        // spec above the chosen preset requires specReason (contract prose rule)
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.reqMemoryMb", 4096))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.specReason"));
        Map<String, Object> bigWithReason = with(
                with(validBody(workspaceId), "vm.reqMemoryMb", 4096),
                "vm.specReason", "동시 접속 부하 테스트를 위해 메모리 증설이 필요합니다.");
        postJson("/api/v1/requests", requesterToken, bigWithReason)
                .andExpect(status().isCreated());

        // end date before start date → 422
        Map<String, Object> badDates = validBody(workspaceId);
        badDates.put("reqStartDate", "2026-08-01");
        badDates.put("reqEndDate", "2026-07-01");
        postJson("/api/v1/requests", requesterToken, badDates)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reqEndDate"));

        // the form carries no domain axis (contract v0.29.0): a submitted
        // desiredSubdomain is an unknown field, ignored — nothing is reserved
        // and the response echoes null (the field survives for OLD requests)
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "desiredSubdomain", "vmr-ignored-x1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vm.desiredSubdomain").value((Object) null))
                .andExpect(jsonPath("$.vm.rootDomain").value((Object) null));

        // missing purpose → 422
        Map<String, Object> noPurpose = validBody(workspaceId);
        noPurpose.remove("purpose");
        postJson("/api/v1/requests", requesterToken, noPurpose)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // submissions are audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.create' and actor_id = ?",
                Long.class, requester.getId());
        assertThat(audits).isPositive();

        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/requests")).andExpect(status().isUnauthorized());
    }

    /**
     * Axis split (v0.23.0): the spec-reason baseline is the CHOSEN flavor, not
     * a per-OS default — the same numbers pass or need a reason depending on
     * which preset the body names. The disk floor stays the OS image's.
     */
    @Test
    void specReasonBaselineFollowsTheChosenFlavor() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-flavor-x1");

        // 'small' as-is (1 vCPU / 1024MiB / 10GiB) → 201, no reason needed
        postJson("/api/v1/requests", requesterToken, bodyFor(workspaceId, smallFlavor))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vm.flavorId").value(smallFlavor.getId()))
                .andExpect(jsonPath("$.vm.reqVcpu").value(1));

        // each axis independently: 2 vCPU exceeds 'small' → specReason required
        postJson("/api/v1/requests", requesterToken,
                with(bodyFor(workspaceId, smallFlavor), "vm.reqVcpu", 2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.specReason"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("선택한 사양 프리셋을 초과하는 신청에는 사유(specReason)를 입력해야 합니다."));
        postJson("/api/v1/requests", requesterToken,
                with(bodyFor(workspaceId, smallFlavor), "vm.reqMemoryMb", 2048))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.specReason"));
        postJson("/api/v1/requests", requesterToken,
                with(bodyFor(workspaceId, smallFlavor), "vm.reqDiskGb", 20))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.specReason"));

        // the very same spec against 'basic' (2 / 2048 / 20) is free
        postJson("/api/v1/requests", requesterToken, bodyFor(workspaceId, basicFlavor))
                .andExpect(status().isCreated());

        // the disk floor is the OS image's min, not the preset's
        postJson("/api/v1/requests", requesterToken,
                with(bodyFor(workspaceId, smallFlavor), "vm.reqDiskGb", 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.reqDiskGb"));
    }

    @Test
    void retiredFlavorIsRejectedAndBothAxesReportIndependently() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-flavor-x2");
        VmFlavor retired = flavorRepository.save(new VmFlavor("vmr-retired", "은퇴 프리셋", 2, 2048, 20,
                CatalogStatus.DISABLED, null));

        postJson("/api/v1/requests", requesterToken, bodyFor(workspaceId, retired))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("vm.flavorId"))
                .andExpect(jsonPath("$.errors[0].message").value("더 이상 선택할 수 없는 사양 프리셋입니다."));

        // both axes retired → one error per axis, and the spec check is skipped
        OsImage retiredImage = imageRepository.save(new OsImage("vmr-disabled-os",
                "비활성 OS", "ubuntu", "24.04", "ubuntu", 1004,
                nodeRepository.findAll().getFirst().getId(), 1, 10,
                CatalogStatus.DISABLED, null));
        postJson("/api/v1/requests", requesterToken,
                with(bodyFor(workspaceId, retired), "vm.imageId", retiredImage.getId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("vm.imageId"))
                .andExpect(jsonPath("$.errors[1].field").value("vm.flavorId"));
    }

    /**
     * A catalog with no ACTIVE row — what a freshly installed environment looks
     * like until an operator registers the images that actually exist on the
     * host. The wizard's OS axis must degrade to an empty list rather than an
     * error, and every way of naming an image must be refused with a stated
     * reason, so no request row can be written against an unusable image.
     */
    @Test
    void emptyOsCatalogListsNothingAndRefusesEverySubmission() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-empty-x1");
        List<OsImage> active = imageRepository.findByStatus(CatalogStatus.ACTIVE);
        assertThat(active).isNotEmpty();
        // Catalog rows are shared state across test classes on this context —
        // the ACTIVE set is restored in the finally block below.
        active.forEach(row -> row.setStatus(CatalogStatus.DISABLED));
        imageRepository.saveAll(active);
        try {
            // the OS axis of the request wizard: 200 with an empty array
            mockMvc.perform(get("/api/v1/os-images")
                            .header("Authorization", "Bearer " + requesterToken))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(0));

            // an image that exists but is no longer selectable → 422 on the field
            postJson("/api/v1/requests", requesterToken,
                    with(validBody(workspaceId), "vm.imageId", image.getId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("vm.imageId"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("더 이상 선택할 수 없는 OS 이미지입니다."));

            // an id that never existed → 404 with a stated reason, not a 500
            postJson("/api/v1/requests", requesterToken,
                    with(validBody(workspaceId), "vm.imageId", 999_999))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("해당 OS 이미지가 존재하지 않습니다."));

            // nothing to pick ⇒ the field left out entirely → 422, never a null deref
            Map<String, Object> withoutImage = validBody(workspaceId);
            @SuppressWarnings("unchecked")
            Map<String, Object> vmSpec = (Map<String, Object>) withoutImage.get("vm");
            vmSpec.remove("imageId");
            postJson("/api/v1/requests", requesterToken, withoutImage)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("vm.imageId"));

            // and none of the three attempts left a row behind
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from requests where workspace_id = ?", Long.class, workspaceId))
                    .isZero();
        } finally {
            active.forEach(row -> row.setStatus(CatalogStatus.ACTIVE));
            imageRepository.saveAll(active);
        }
    }

    @Test
    void displayNameIsLengthCappedAndEchoed() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-dname-x1");

        // over 100 chars → 422 (bean validation)
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "displayName", "가".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));

        // echoed in the detail; omitted → null
        String response = postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "displayName", "데이터분석 실습 서버"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("데이터분석 실습 서버"))
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(response).get("id").asLong();
        mockMvc.perform(get("/api/v1/requests/" + requestId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("데이터분석 실습 서버"));

        postJson("/api/v1/requests", requesterToken, validBody(workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value((Object) null));
    }

    @Test
    void desiredSlugIsValidatedEchoedAndNeverRecycled() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-slug-x1");

        // desiredSlug echoed in the detail; omitted → null
        String mine = postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "vm.desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vm.desiredSlug").value("vmr-slug-mine"))
                .andReturn().getResponse().getContentAsString();
        long mineId = objectMapper.readTree(mine).get("id").asLong();
        postJson("/api/v1/requests", requesterToken, validBody(workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vm.desiredSlug").value((Object) null));

        // malformed slug → 422 (bean validation pattern)
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.desiredSlug", "-bad-"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.desiredSlug"));
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.desiredSlug", "ab"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.desiredSlug"));

        // reserved word (shared with reservedSubdomains) → 422
        postJson("/api/v1/requests", requesterToken, with(validBody(workspaceId), "vm.desiredSlug", "www"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.desiredSlug"));

        // another SUBMITTED request already asks for the slug → 422
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "vm.desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.desiredSlug"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 신청 중인 호스트명입니다."));

        // an existing vms.hostname blocks the slug — even soft-deleted (never recycled)
        long vmReqId = submit(requesterToken, workspaceId);
        Vm vm = vmRepository.save(new Vm(nodeRepository.findAll().getFirst().getId(), workspaceId,
                org.getId(), vmReqId, "vmr-slug-taken", "vmr-slug-taken", image.getId(),
                image.getSshUsername(), 1, 1024, 10, null, null));
        jdbcTemplate.update(
                "update vms set deleted_at = now(), status = 'DELETED'::vm_status where id = ?",
                vm.getId());
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "vm.desiredSlug", "vmr-slug-taken"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.desiredSlug"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 사용 중인 호스트명입니다."));

        // a rejected request is terminal — its desired slug is submittable again
        User orgAdmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        String orgAdminToken = jwtService.createAccessToken(orgAdmin);
        postJson("/api/v1/admin/requests/" + mineId + "/reject", orgAdminToken,
                Map.of("comment", "슬러그 재사용 테스트를 위해 반려합니다."))
                .andExpect(status().isOk());
        postJson("/api/v1/requests", requesterToken,
                with(validBody(workspaceId), "vm.desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vm.desiredSlug").value("vmr-slug-mine"));
    }

    @Test
    void listAndDetailVisibilityFollowsMembership() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-visib-x1");
        addMember(requesterToken, workspaceId, member.getEmail(), "MEMBER");
        long first = submit(requesterToken, workspaceId);
        long second = submit(requesterToken, workspaceId);

        // a plain member sees the workspace's requests, newest first
        mockMvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first));

        // paging envelope: size=1 → two pages
        mockMvc.perform(get("/api/v1/requests?size=1").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        // invalid paging → 422
        mockMvc.perform(get("/api/v1/requests?size=101").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // outsider sees nothing and cannot filter by this workspace
        mockMvc.perform(get("/api/v1/requests").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/requests?workspaceId=" + workspaceId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // member workspaceId + status filters
        mockMvc.perform(get("/api/v1/requests?workspaceId=" + workspaceId + "&status=SUBMITTED")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/requests?workspaceId=" + workspaceId + "&status=CANCELED")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // detail: participant ok, outsider 403, unknown id 404
        mockMvc.perform(get("/api/v1/requests/" + first).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.review").value((Object) null));
        mockMvc.perform(get("/api/v1/requests/" + first).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/requests/999999").header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void cancelIsForRequesterOrWorkspaceManagersAndOnlyOnce() throws Exception {
        long workspaceId = createTeam(requesterToken, "vmr-cancel-x1");
        addMember(requesterToken, workspaceId, otherMember.getEmail(), "MEMBER");
        long first = submit(requesterToken, workspaceId);
        // the workspace's owner is the requester of `first`, so the second request
        // has to come from somebody else for the owner's reach to mean anything
        long second = submit(otherMemberToken, workspaceId);

        // a fellow member who did not ask, and an outsider, cannot cancel → 403
        postJson("/api/v1/requests/" + first + "/cancel", otherMemberToken, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        postJson("/api/v1/requests/" + first + "/cancel", outsiderToken, Map.of())
                .andExpect(status().isForbidden());

        // requester cancels own request → 200 CANCELED
        postJson("/api/v1/requests/" + first + "/cancel", requesterToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // second cancel → 409 REQUEST_ALREADY_DECIDED
        postJson("/api/v1/requests/" + first + "/cancel", requesterToken, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));

        // the workspace's OWNER may cancel another member's request
        postJson("/api/v1/requests/" + second + "/cancel", requesterToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // unknown request → 404
        postJson("/api/v1/requests/999999/cancel", requesterToken, Map.of())
                .andExpect(status().isNotFound());

        // cancellations are audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.cancel' and target_id in (?, ?)",
                Long.class, first, second);
        assertThat(audits).isEqualTo(2);
    }

    /** A body prefilled from the chosen flavor — exactly what the wizard posts. */
    private Map<String, Object> validBody(long workspaceId) {
        return bodyFor(workspaceId, basicFlavor);
    }

    private Map<String, Object> bodyFor(long workspaceId, VmFlavor flavor) {
        Map<String, Object> vm = new HashMap<>();
        vm.put("imageId", image.getId());
        vm.put("flavorId", flavor.getId());
        vm.put("reqVcpu", flavor.getVcpu());
        vm.put("reqMemoryMb", flavor.getMemoryMb());
        vm.put("reqDiskGb", flavor.getDiskGb());
        Map<String, Object> body = new HashMap<>();
        body.put("type", "VM");
        body.put("workspaceId", workspaceId);
        body.put("orgId", org.getId());
        body.put("purpose", "수업 실습용 서버");
        body.put("vm", vm);
        return body;
    }

    /** Dotted keys address the nested per-type member: {@code with(body, "vm.imageId", 1)}. */
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

    private long submit(String token, long workspaceId) throws Exception {
        String response = postJson("/api/v1/requests", token, validBody(workspaceId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createTeam(String token, String slug) throws Exception {
        String body = postJson("/api/v1/workspaces", token,
                Map.of("kind", "TEAM", "name", "테스트 워크스페이스 " + slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Sudo-mode gate: mint the caller's X-Reauth-Token for the protected call. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private void addMember(String token, long workspaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
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

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
