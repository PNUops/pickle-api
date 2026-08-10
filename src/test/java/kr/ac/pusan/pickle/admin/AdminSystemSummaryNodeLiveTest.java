package kr.ac.pusan.pickle.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The system dashboard is what an operator opens when Proxmox is down, so a
 * dead hypervisor must be a value on the panel and never a 500 that hides it.
 * The client is stubbed to refuse here rather than left to fail on its own:
 * "the host happens to be unreachable from the test machine" would assert the
 * same thing by accident and stop asserting it the day that changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminSystemSummaryNodeLiveTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ProxmoxClient proxmoxClient;

    private String sysAdminToken;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
    }

    @Test
    void anUnreachableHypervisorDegradesTheTileInsteadOfFailingThePanel() throws Exception {
        when(proxmoxClient.nodeStatus(anyString(), anyString()))
                .thenThrow(new ProxmoxApiException("Proxmox API transport failure on GET"
                        + " /api2/json/nodes/pve1/status: connect timed out", null));

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive").isArray())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].nodeId").exists())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(false))
                .andExpect(jsonPath("$.nodesLive[?(@.reachable == true)]").isEmpty());
    }
}
