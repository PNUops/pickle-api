package kr.ac.pusan.pickle.reference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reference data per contract: GET /orgs (ACTIVE only; hidden orgs filtered
 * for USER tokens, visible to manager tiers), the two request axes
 * GET /templates (OS catalog) and GET /vm-flavors (spec presets), both
 * ACTIVE only, and GET /meta/request-options (settings-backed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ReferenceDataTest {

    @Autowired
    private MockMvc mockMvc;

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
        for (String path : new String[] {"/api/v1/orgs", "/api/v1/templates", "/api/v1/vm-flavors",
                "/api/v1/meta/request-options"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        }
    }

    @Test
    void listsOnlyActiveVisibleOrgsForUsers() throws Exception {
        orgRepository.findBySlug("ref-disabled").orElseGet(() -> {
            Org disabled = new Org("비활성 기관", "ref-disabled", null);
            disabled.setStatus(OrgStatus.DISABLED);
            return orgRepository.save(disabled);
        });
        orgRepository.findBySlug("ref-visible").orElseGet(() ->
                orgRepository.save(new Org("공개 기관", "ref-visible", null)));

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // a plain ACTIVE org is listed as a plain array
                .andExpect(jsonPath("$[?(@.slug == 'ref-visible')].name")
                        .value(org.hamcrest.Matchers.contains("공개 기관")))
                // the seed org is hidden: filtered for USER tokens
                .andExpect(jsonPath("$[?(@.slug == '" + SeedFixtures.ORG_SLUG + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.slug == 'ref-disabled')]").isEmpty());
    }

    @Test
    void managerTierSeesHiddenOrgs() throws Exception {
        User orgadmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        String managerToken = jwtService.createAccessToken(orgadmin);

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == '" + SeedFixtures.ORG_SLUG + "')].name")
                        .value(org.hamcrest.Matchers.contains(SeedFixtures.ORG_NAME)))
                .andExpect(jsonPath("$[?(@.slug == '" + SeedFixtures.ORG_SLUG + "')].hidden")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void listsOnlyActiveTemplatesAsAPureOsCatalog() throws Exception {
        // a DISABLED version row must not surface in the wizard list
        if (osImageRepository.findByStatusOrderByIdAsc(TemplateStatus.DISABLED).isEmpty()) {
            Long nodeId = osImageRepository.findAll().getFirst().getNodeId();
            osImageRepository.save(new OsImage("ubuntu-22.04", "Ubuntu 22.04 LTS (구버전)", 1001,
                    nodeId, 1, 10, TemplateStatus.DISABLED, null));
        }

        mockMvc.perform(get("/api/v1/templates").header("Authorization", "Bearer " + accessToken))
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
                // the spec fields moved to the flavor axis
                .andExpect(jsonPath("$[0].defaultVcpu").doesNotExist())
                .andExpect(jsonPath("$[0].defaultMemoryMb").doesNotExist())
                .andExpect(jsonPath("$[0].defaultDiskGb").doesNotExist());
    }

    @Test
    void listsOnlyActiveFlavorsWithTheirSpecs() throws Exception {
        // a retired preset must not surface in the wizard list
        if (vmFlavorRepository.findByStatusOrderByIdAsc(TemplateStatus.DISABLED).isEmpty()) {
            vmFlavorRepository.save(new VmFlavor("ref-retired", "은퇴 프리셋", 8, 16384, 80,
                    TemplateStatus.DISABLED, null));
        }

        mockMvc.perform(get("/api/v1/vm-flavors").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.name == 'ref-retired')]").isEmpty())
                // V58 presets, ordered by id
                .andExpect(jsonPath("$[0].name").value("small"))
                .andExpect(jsonPath("$[0].displayName").value("소형"))
                .andExpect(jsonPath("$[0].vcpu").value(1))
                .andExpect(jsonPath("$[0].memoryMb").value(1024))
                .andExpect(jsonPath("$[0].diskGb").value(10))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].notes").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("basic"))
                .andExpect(jsonPath("$[1].displayName").value("기본형"))
                .andExpect(jsonPath("$[1].vcpu").value(2))
                .andExpect(jsonPath("$[1].memoryMb").value(2048))
                .andExpect(jsonPath("$[1].diskGb").value(20))
                .andExpect(jsonPath("$[2].name").value("large"))
                .andExpect(jsonPath("$[2].displayName").value("대형"))
                .andExpect(jsonPath("$[2].vcpu").value(4))
                .andExpect(jsonPath("$[2].memoryMb").value(8192))
                .andExpect(jsonPath("$[2].diskGb").value(40));
    }

    @Test
    void requestOptionsComeFromSettings() throws Exception {
        mockMvc.perform(get("/api/v1/meta/request-options").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedRootDomains").value(
                        org.hamcrest.Matchers.contains("pickle.pnuops.com")))
                // V55/V57 expanded the seed to ~400 entries — assert the original
                // head, representatives of each new family, and the exact size.
                .andExpect(jsonPath("$.reservedSubdomains").value(org.hamcrest.Matchers.hasItems(
                        "www", "api", "admin", "ssh", "mail", "console", "staging",
                        "portal", "webmail", "jupyter", "vpn", "pickle", "grafana", "dev",
                        "plato", "auth", "pnu")))
                .andExpect(jsonPath("$.reservedSubdomains.length()").value(416))
                // v0.12.0: SSH gateway host for the request form's slug preview
                .andExpect(jsonPath("$.sshHost").value("ssh.pickle.pnuops.com"));
    }
}
