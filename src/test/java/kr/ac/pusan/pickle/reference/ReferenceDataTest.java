package kr.ac.pusan.pickle.reference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
 * Reference data per contract: GET /orgs (ACTIVE only), GET /templates
 * (ACTIVE only, V3 presets), GET /meta/request-options (settings-backed).
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
    private VmTemplateRepository vmTemplateRepository;

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
        for (String path : new String[] {"/api/v1/orgs", "/api/v1/templates", "/api/v1/meta/request-options"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        }
    }

    @Test
    void listsOnlyActiveOrgs() throws Exception {
        orgRepository.findBySlug("ref-disabled").orElseGet(() -> {
            Org disabled = new Org("비활성 기관", "ref-disabled", null);
            disabled.setStatus(OrgStatus.DISABLED);
            return orgRepository.save(disabled);
        });

        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // seeded dev org (sw-edu) is ACTIVE and listed as a plain array
                .andExpect(jsonPath("$[?(@.slug == 'sw-edu')].name")
                        .value(org.hamcrest.Matchers.contains("SW교육센터")))
                .andExpect(jsonPath("$[?(@.slug == 'ref-disabled')]").isEmpty());
    }

    @Test
    void listsOnlyActiveTemplatesWithPresetDefaults() throws Exception {
        // a DISABLED version row must not surface in the wizard list
        if (vmTemplateRepository.findByStatusOrderByIdAsc(TemplateStatus.DISABLED).isEmpty()) {
            Long nodeId = vmTemplateRepository.findAll().getFirst().getNodeId();
            vmTemplateRepository.save(new VmTemplate("ubuntu-22.04", "Ubuntu 22.04 LTS (구버전)", 9000,
                    nodeId, 1, 2, 2048, 20, 10, TemplateStatus.DISABLED, null));
        }

        mockMvc.perform(get("/api/v1/templates").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.name == 'ubuntu-22.04')]").isEmpty())
                // V3 presets
                .andExpect(jsonPath("$[0].name").value("ubuntu-24.04"))
                .andExpect(jsonPath("$[0].defaultVcpu").value(2))
                .andExpect(jsonPath("$[0].defaultMemoryMb").value(2048))
                .andExpect(jsonPath("$[0].defaultDiskGb").value(20))
                .andExpect(jsonPath("$[0].minDiskGb").value(10))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].notes").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("ubuntu-24.04-small"))
                .andExpect(jsonPath("$[1].defaultVcpu").value(1))
                .andExpect(jsonPath("$[1].defaultMemoryMb").value(1024))
                .andExpect(jsonPath("$[1].defaultDiskGb").value(10))
                .andExpect(jsonPath("$[2].name").value("ubuntu-24.04-large"))
                .andExpect(jsonPath("$[2].defaultVcpu").value(4))
                .andExpect(jsonPath("$[2].defaultMemoryMb").value(8192))
                .andExpect(jsonPath("$[2].defaultDiskGb").value(40));
    }

    @Test
    void requestOptionsComeFromSettings() throws Exception {
        mockMvc.perform(get("/api/v1/meta/request-options").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedRootDomains").value(
                        org.hamcrest.Matchers.contains("pickle.pnuops.com")))
                .andExpect(jsonPath("$.reservedSubdomains").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "www", "api", "admin", "ssh", "mail", "console", "staging")))
                // v0.12.0: SSH gateway host for the request form's slug preview
                .andExpect(jsonPath("$.sshHost").value("ssh.pickle.pnuops.com"));
    }
}
