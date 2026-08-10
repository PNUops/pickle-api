package kr.ac.pusan.pickle.admin;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.NodeStatusInfo;
import kr.ac.pusan.pickle.proxmox.dto.NodeStorageStatus;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The system dashboard is what an operator opens when Proxmox is down, so a
 * dead hypervisor must be a value on the panel and never a 500 that hides it.
 * The client is stubbed to refuse here rather than left to fail on its own:
 * "the host happens to be unreachable from the test machine" would assert the
 * same thing by accident and stop asserting it the day that changes.
 *
 * <p>The other cases the live block has to keep apart: a node that answered its
 * status but not its storage is reachable with the storage half missing, a
 * status reply carrying no payload is not an answer at all, and a node the
 * operator marked OFFLINE is not asked in the first place while still holding
 * its row on the panel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminSystemSummaryNodeLiveTest {

    private static final NodeStatusInfo STATUS = new NodeStatusInfo(
            new NodeStatusInfo.CpuInfo(32, 16, 2, "test"),
            new NodeStatusInfo.MemoryInfo(64_000_000_000L, 24_000_000_000L,
                    40_000_000_000L, 40_000_000_000L),
            0.125);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProxmoxClient proxmoxClient;

    private String sysAdminToken;

    private final List<Long> createdNodeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
    }

    @AfterEach
    void tearDown() {
        createdNodeIds.forEach(id -> jdbcTemplate.update("delete from nodes where id = ?", id));
        createdNodeIds.clear();
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

    /**
     * The storage read needs {@code Datastore.Audit}, which the status read does
     * not: losing that one right must cost the two storage numbers and nothing
     * else, or the panel claims a node is down while it is answering.
     */
    @Test
    void aStorageOnlyFailureKeepsTheNodeReachable() throws Exception {
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(STATUS);
        when(proxmoxClient.nodeStorage(anyString(), anyString()))
                .thenThrow(new ProxmoxApiException(403, "Permission check failed",
                        "GET /nodes/pve1/storage"));

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(true))
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].memUsedBytes")
                        .value(24_000_000_000L))
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].storageTotalBytes",
                        contains(nullValue())))
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].storageUsedBytes",
                        contains(nullValue())));
    }

    /**
     * The client hands back whatever {@code {"data": …}} carried, so a 200 with
     * an empty envelope reaches the caller as null and must read as "not
     * answering" instead of taking the whole dashboard down with it.
     */
    @Test
    void aStatusReplyWithNoPayloadIsNotAnAnswer() throws Exception {
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(false))
                .andExpect(jsonPath("$.nodesLive[?(@.reachable == true)]").isEmpty());
    }

    /**
     * A node the operator marked OFFLINE is known to be down, so probing it only
     * buys a timeout per dashboard load. It keeps its row so the node count
     * stays whole.
     */
    @Test
    void anOfflineNodeIsNotProbedButStillHoldsItsRow() throws Exception {
        String name = "assnl-offline-" + UUID.randomUUID().toString().substring(0, 8);
        createOfflineNode(name);
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(STATUS);
        when(proxmoxClient.nodeStorage(anyString(), anyString())).thenReturn(List.of(
                new NodeStorageStatus("local-lvm", "lvmthin", 1, 1, 2_000_000_000_000L,
                        500_000_000_000L, 1_500_000_000_000L, 0.25)));

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive[?(@.name == '" + name + "')].nodeId").exists())
                .andExpect(jsonPath("$.nodesLive[?(@.name == '" + name + "')].reachable")
                        .value(false))
                // The nodes that are not OFFLINE still answer, so the skip is a
                // filter on that one node and not the block giving up.
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(true));

        verify(proxmoxClient, never()).nodeStatus(anyString(), eq(name));
        verify(proxmoxClient, never()).nodeStorage(anyString(), eq(name));
    }

    private void createOfflineNode(String name) {
        Long id = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb,
                                   vm_bridge, storage)
                values (?, 'https://pve-offline:8006', 'OFFLINE', 16, 32768, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, name);
        createdNodeIds.add(id);
    }
}
