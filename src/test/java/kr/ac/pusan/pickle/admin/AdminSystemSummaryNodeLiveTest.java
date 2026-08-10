package kr.ac.pusan.pickle.admin;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.NodeRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The system dashboard is what an operator opens when Proxmox is down, so a
 * dead hypervisor must be a value on the panel and never a 500 that hides it.
 * The client is stubbed to refuse here rather than left to fail on its own:
 * "the host happens to be unreachable from the test machine" would assert the
 * same thing by accident and stop asserting it the day that changes.
 *
 * <p>The other cases the live block has to keep apart: a node that answered its
 * status but not its storage is reachable with the storage half missing and
 * says so in the coverage counts, a status reply carrying no payload is not an
 * answer at all, a storage entry PVE sent without a name is not a crash, and a
 * node the operator marked OFFLINE is asked like any other because its guests
 * are still running on it.
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

    @MockitoSpyBean
    private NodeRepository nodeRepository;

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
                        contains(nullValue())))
                // The node stays on the panel, but the platform storage sum is
                // now over fewer nodes than the platform has, and a client that
                // sums has to be able to see that.
                .andExpect(jsonPath("$.liveCoverage.nodeCount").value(1))
                .andExpect(jsonPath("$.liveCoverage.memoryMeasuredNodeCount").value(1))
                .andExpect(jsonPath("$.liveCoverage.storageMeasuredNodeCount").value(0));
    }

    /**
     * The storage list comes back as whatever the PVE envelope held, so an
     * entry can arrive without the name the match is made on. The node column
     * is NOT NULL and the PVE side is not, so the comparison runs from the
     * column — otherwise one nameless entry takes the whole dashboard down.
     */
    @Test
    void aStorageEntryWithoutANameDoesNotFailThePanel() throws Exception {
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(STATUS);
        when(proxmoxClient.nodeStorage(anyString(), anyString())).thenReturn(List.of(
                new NodeStorageStatus(null, "dir", 1, 1, 2_000_000_000_000L,
                        500_000_000_000L, 1_500_000_000_000L, 0.25)));

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(true))
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].storageTotalBytes",
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
     * OFFLINE keeps a node out of new placements and leaves the guests it
     * already has running, so its memory is still spoken for. Asking it is the
     * only way the platform memory tile can count that usage, and a node that
     * really is down still degrades on its own without taking the panel.
     */
    @Test
    void anOfflineNodeIsStillProbedBecauseItsGuestsKeepRunning() throws Exception {
        String name = "assnl-offline-" + UUID.randomUUID().toString().substring(0, 8);
        createOfflineNode(name);
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(STATUS);
        when(proxmoxClient.nodeStorage(anyString(), anyString())).thenReturn(List.of(
                new NodeStorageStatus("local-lvm", "lvmthin", 1, 1, 2_000_000_000_000L,
                        500_000_000_000L, 1_500_000_000_000L, 0.25)));

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesLive[?(@.name == '" + name + "')].reachable")
                        .value(true))
                .andExpect(jsonPath("$.nodesLive[?(@.name == '" + name + "')].memUsedBytes")
                        .value(24_000_000_000L))
                .andExpect(jsonPath("$.nodesLive[?(@.name == 'pve1')].reachable").value(true))
                .andExpect(jsonPath("$.liveCoverage.nodeCount").value(2))
                .andExpect(jsonPath("$.liveCoverage.memoryMeasuredNodeCount").value(2));

        verify(proxmoxClient).nodeStatus(anyString(), eq(name));
        verify(proxmoxClient).nodeStorage(anyString(), eq(name));
    }

    /**
     * The ratio list and the live list are two halves of one panel. Read from
     * two separate queries they can straddle a status change and describe
     * different rows, and the console reads a node that is ACTIVE in one half
     * and unreachable in the other as an outage nobody caused. One read of the
     * node table is what keeps them consistent.
     */
    @Test
    void bothHalvesOfThePanelAreBuiltFromOneReadOfTheNodeTable() throws Exception {
        when(proxmoxClient.nodeStatus(anyString(), anyString())).thenReturn(STATUS);

        mockMvc.perform(get("/api/v1/admin/system-summary")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());

        verify(nodeRepository).findAll(any(Sort.class));
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
