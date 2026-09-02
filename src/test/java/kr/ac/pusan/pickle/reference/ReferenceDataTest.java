package kr.ac.pusan.pickle.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import kr.ac.pusan.pickle.security.JwtService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reference data per contract: GET /orgs (ACTIVE only; hidden orgs filtered
 * for USER tokens, visible to manager tiers), the request form's axes
 * GET /os-images (OS catalog), GET /vm-flavors (specs) and
 * GET /request-periods (usage periods), all offerable-only, and
 * GET /meta/request-options (settings-backed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ReferenceDataTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private OsImageRepository osImageRepository;

    @Autowired
    private VmFlavorRepository vmFlavorRepository;

    @Autowired
    private JwtService jwtService;

    private String accessToken;

    @BeforeEach
    void setUp() {
        User user = userRepository.findByEmail("ref.reader@pusan.ac.kr").orElseGet(() -> {
            User created = new User("ref.reader@pusan.ac.kr", "{noop}", "참조조회자");
            created.setStatus(UserStatus.ACTIVE);
            created.setEmailVerifiedAt(Instant.now());
            return userRepository.save(created);
        });
        accessToken = jwtService.createAccessToken(user);
    }

    @Test
    void referenceEndpointsRequireAuthentication() throws Exception {
        for (String path : new String[] {"/api/v1/orgs", "/api/v1/os-images", "/api/v1/vm-flavors",
                "/api/v1/request-periods", "/api/v1/meta/request-options"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        }
    }

    @Test
    void listsOnlyActiveVisibleOrgsForUsers() throws Exception {
        orgRepository.findFirstByNameOrderByIdAsc("참조 비활성 기관").orElseGet(() -> {
            Org disabled = new Org("참조 비활성 기관", null);
            disabled.setStatus(OrgStatus.DISABLED);
            return orgRepository.save(disabled);
        });
        orgRepository.findFirstByNameOrderByIdAsc("공개 기관").orElseGet(() ->
                orgRepository.save(new Org("공개 기관", null)));

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // a plain ACTIVE org is listed as a plain array
                .andExpect(jsonPath("$[?(@.name == '공개 기관')].name")
                        .value(org.hamcrest.Matchers.contains("공개 기관")))
                // the seed org is hidden: filtered for USER tokens
                .andExpect(jsonPath("$[?(@.name == '" + SeedFixtures.ORG_NAME + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.name == '참조 비활성 기관')]").isEmpty());
    }

    @Test
    void managerTierSeesHiddenOrgs() throws Exception {
        User orgadmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        String managerToken = jwtService.createAccessToken(orgadmin);

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + SeedFixtures.ORG_NAME + "')].name")
                        .value(org.hamcrest.Matchers.contains(SeedFixtures.ORG_NAME)))
                .andExpect(jsonPath("$[?(@.name == '" + SeedFixtures.ORG_NAME + "')].hidden")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void listsOnlyActiveOsImagesAsAPureOsCatalog() throws Exception {
        // a DISABLED version row must not surface in the wizard list
        if (osImageRepository.findByStatus(CatalogStatus.DISABLED).isEmpty()) {
            Long nodeId = osImageRepository.findAll().getFirst().getNodeId();
            osImageRepository.save(new OsImage("ubuntu-22.04", "Ubuntu 22.04 LTS (구버전)",
                    "ubuntu", "22.04", "ubuntu", 1001, nodeId, 1, 10,
                    CatalogStatus.DISABLED, null));
        }

        mockMvc.perform(get("/api/v1/os-images").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // the axis split folded the -small/-large preset rows away: one OS, one row
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[?(@.name == 'ubuntu-22.04')]").isEmpty())
                .andExpect(jsonPath("$[0].name").value("ubuntu-24.04"))
                .andExpect(jsonPath("$[0].displayName").isNotEmpty())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].minDiskGb").value(10))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].notes").isNotEmpty())
                // distribution identity + guest account: the catalog row carries
                // them, and the backfill left the seeded image on ubuntu
                .andExpect(jsonPath("$[0].osFamily").value("ubuntu"))
                .andExpect(jsonPath("$[0].osVersion").value("24.04"))
                .andExpect(jsonPath("$[0].sshUsername").value("ubuntu"))
                // the spec fields moved to the flavor axis
                .andExpect(jsonPath("$[0].defaultVcpu").doesNotExist())
                .andExpect(jsonPath("$[0].defaultMemoryMb").doesNotExist())
                .andExpect(jsonPath("$[0].defaultDiskGb").doesNotExist());
    }

    /**
     * The wizard's OS axis reads as a rule, not as the order an operator
     * happened to register rows in: distribution alphabetically, release
     * descending as a number so the newest sits at the top of its family. The
     * rows below defeat both of the ways this can be got wrong — Rocky 10 is
     * registered before Rocky 9 (so id order would show 10 first for the wrong
     * reason) and the release strings sort the other way as text ('10' &lt;
     * '9'), so only numeric release order puts 10 ahead of 9.
     */
    @Test
    void osCatalogIsOrderedByFamilyThenNewestReleaseFirst() throws Exception {
        Long nodeId = osImageRepository.findAll().getFirst().getNodeId();
        List<OsImage> added = osImageRepository.saveAll(List.of(
                new OsImage("rocky-10", "Rocky Linux 10", "rocky", "10", "rocky",
                        1901, nodeId, 1, 10, CatalogStatus.ACTIVE, "정렬 확인용"),
                new OsImage("rocky-9", "Rocky Linux 9", "rocky", "9", "rocky",
                        1902, nodeId, 1, 10, CatalogStatus.ACTIVE, "정렬 확인용"),
                new OsImage("debian-13", "Debian 13", "debian", "13", "debian",
                        1903, nodeId, 1, 10, CatalogStatus.ACTIVE, "정렬 확인용"),
                new OsImage("ubuntu-20.04", "Ubuntu 20.04 LTS", "ubuntu", "20.04", "ubuntu",
                        1904, nodeId, 1, 10, CatalogStatus.ACTIVE, "정렬 확인용")));
        try {
            mockMvc.perform(get("/api/v1/os-images")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains(
                            "debian-13", "rocky-10", "rocky-9",
                            "ubuntu-24.04", "ubuntu-20.04")));
        } finally {
            osImageRepository.deleteAll(added);
        }
    }

    /**
     * The spec axis carries no scale to sort by — the seeded rows are shapes
     * (compute heavy, memory heavy), and no arithmetic says which of those
     * comes first. So the order is the stated one, and the two rows below are
     * registered last with display orders that must still bracket the seeded
     * pair.
     */
    @Test
    void flavorsAreOrderedByTheirStatedDisplayOrder() throws Exception {
        List<VmFlavor> added = vmFlavorRepository.saveAll(List.of(
                new VmFlavor("ref-order-last", "정렬 확인용 마지막", 8, 16384, 80,
                        CatalogStatus.ACTIVE, "정렬 확인용", 900),
                new VmFlavor("ref-order-first", "정렬 확인용 처음", 1, 512, 5,
                        CatalogStatus.ACTIVE, "정렬 확인용", -900)));
        try {
            mockMvc.perform(get("/api/v1/vm-flavors")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains(
                            "ref-order-first", "highcpu", "highmem", "ref-order-last")))
                    .andExpect(jsonPath("$[1].displayOrder").value(1))
                    .andExpect(jsonPath("$[2].displayOrder").value(2));
        } finally {
            vmFlavorRepository.deleteAll(added);
        }
    }

    @Test
    void listsOnlyActiveFlavorsWithTheirSpecs() throws Exception {
        // a retired spec must not surface in the wizard list
        if (vmFlavorRepository.findByStatus(CatalogStatus.DISABLED).isEmpty()) {
            vmFlavorRepository.save(new VmFlavor("ref-retired", "은퇴 사양", 8, 16384, 80,
                    CatalogStatus.DISABLED, null, 800));
        }

        mockMvc.perform(get("/api/v1/vm-flavors").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name == 'ref-retired')]").isEmpty())
                // the two seeded shapes, in the order the operator stated
                .andExpect(jsonPath("$[0].name").value("highcpu"))
                .andExpect(jsonPath("$[0].displayName").value("컴퓨팅 최적화"))
                .andExpect(jsonPath("$[0].vcpu").value(2))
                .andExpect(jsonPath("$[0].memoryMb").value(1024))
                .andExpect(jsonPath("$[0].diskGb").value(32))
                .andExpect(jsonPath("$[0].displayOrder").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].notes").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("highmem"))
                .andExpect(jsonPath("$[1].displayName").value("메모리 최적화"))
                .andExpect(jsonPath("$[1].vcpu").value(1))
                .andExpect(jsonPath("$[1].memoryMb").value(2048))
                .andExpect(jsonPath("$[1].diskGb").value(32))
                .andExpect(jsonPath("$[1].displayOrder").value(2));
    }

    /**
     * 신청 화면의 기간 축. 관리자가 등록한 순서대로 나오고, 종료일이 없는 항목은
     * 무기한이라 그 자리에 null 이 실린다.
     */
    @Test
    void listsOfferablePeriodsInTheirStatedOrder() throws Exception {
        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[*].displayName").value(org.hamcrest.Matchers.contains(
                        "이번 학기", "이번 방학", "무기한 (교내 서비스)")))
                .andExpect(jsonPath("$[0].endDate").isNotEmpty())
                .andExpect(jsonPath("$[2].endDate").value((Object) null))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    void requestOptionsComeFromSettings() throws Exception {
        mockMvc.perform(get("/api/v1/meta/request-options").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedRootDomains").value(
                        org.hamcrest.Matchers.contains("pusan.dev")))
                // The reserved list this endpoint hands back is whatever the
                // deployment has configured; a development database carries the
                // short representative set the seeder writes. Asserting the
                // several-hundred-entry production list here would pin an
                // operations decision to a test about the endpoint.
                .andExpect(jsonPath("$.reservedSubdomains").value(org.hamcrest.Matchers.contains(
                        "www", "api", "admin", "ssh", "mail", "console", "staging")))
                // v0.12.0: SSH gateway host for the request form's slug preview
                .andExpect(jsonPath("$.sshHost").value("ssh.pcl.kr"));
    }

    // The admin settings screen renders `description` verbatim, so it has to stay
    // Korean. Whoever creates a settings row writes its description, so a row
    // created with English text puts English in front of users with nothing to
    // correct it afterwards — which is what this pins.
    @Test
    void settingDescriptionsStayKorean() throws Exception {
        for (String key : List.of("allowed_root_domains", "reserved_subdomains",
                "profanity_subdomains")) {
            String description = jdbcTemplate.queryForObject(
                    "select description from settings where key = ?", String.class, key);
            assertThat(description)
                    .as("settings.%s description is user-facing and must be Korean", key)
                    .matches(".*[\\uAC00-\\uD7A3].*");
        }
    }
}
