package kr.ac.pusan.pickle.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.IpPoolSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code GET /admin/nodes} (SYS_ADMIN): per-node capacity vs
 * allocation aggregates with the operator-tunable warning thresholds
 * (overcommit view) and the node pool's occupancy.
 */
@Service
public class AdminNodeQueryService {

    /** Threshold fallbacks when the settings rows are missing (V3 seeds). */
    static final double DEFAULT_VCPU_OVERCOMMIT_WARN = 3.0;
    static final double DEFAULT_MEMORY_USAGE_WARN = 0.8;

    private final NodeRepository nodeRepository;
    private final IpamService ipamService;
    private final SettingsService settingsService;
    private final JdbcTemplate jdbcTemplate;

    public AdminNodeQueryService(NodeRepository nodeRepository, IpamService ipamService,
            SettingsService settingsService, JdbcTemplate jdbcTemplate) {
        this.nodeRepository = nodeRepository;
        this.ipamService = ipamService;
        this.settingsService = settingsService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Single-node summary — the status-transition op returns the refreshed row. */
    @Transactional(readOnly = true)
    public NodeSummaryResponse getNode(long nodeId) {
        Node node = nodeRepository.findById(nodeId).orElseThrow();
        double cpuWarn = settingsService.decimal(SettingsService.VCPU_OVERCOMMIT_WARN,
                DEFAULT_VCPU_OVERCOMMIT_WARN);
        double memoryWarn = settingsService.decimal(SettingsService.MEMORY_USAGE_WARN,
                DEFAULT_MEMORY_USAGE_WARN);
        return toSummary(node, loadAllocations().getOrDefault(node.getId(), NodeAllocation.EMPTY),
                cpuWarn, memoryWarn);
    }

    @Transactional(readOnly = true)
    public List<NodeSummaryResponse> listNodes() {
        return listNodes(nodeRepository.findAll(Sort.by("id")));
    }

    /**
     * The same summaries over node rows the caller already read. The system
     * dashboard builds two halves from one node list — these ratios and the
     * live hypervisor probe — and reads the rows once so a status change
     * landing mid-request cannot leave the two halves describing different
     * rows. Only basic columns are touched, so rows read outside this
     * transaction are fine.
     */
    @Transactional(readOnly = true)
    public List<NodeSummaryResponse> listNodes(List<Node> nodes) {
        double cpuWarn = settingsService.decimal(SettingsService.VCPU_OVERCOMMIT_WARN,
                DEFAULT_VCPU_OVERCOMMIT_WARN);
        double memoryWarn = settingsService.decimal(SettingsService.MEMORY_USAGE_WARN,
                DEFAULT_MEMORY_USAGE_WARN);
        Map<Long, NodeAllocation> allocations = loadAllocations();
        return nodes.stream()
                .map(node -> toSummary(node, allocations.getOrDefault(node.getId(),
                        NodeAllocation.EMPTY), cpuWarn, memoryWarn))
                .toList();
    }

    private NodeSummaryResponse toSummary(Node node, NodeAllocation allocation, double cpuWarn,
            double memoryWarn) {
        IpPoolSummaryResponse ipPool = node.getIpPoolId() == null ? null
                : IpPoolSummaryResponse.from(ipamService.poolUsage(node.getIpPoolId()));
        double cpuRatio = node.getCpuThreads() == 0 ? 0.0
                : round2((double) allocation.vcpu() / node.getCpuThreads());
        double memoryRatio = node.getMemoryMb() == 0 ? 0.0
                : round2((double) allocation.memoryMb() / node.getMemoryMb());
        return new NodeSummaryResponse(node.getId(), node.getName(), node.getStatus(),
                node.getCpuThreads(), node.getMemoryMb(), node.getVmBridge(), node.getStorage(),
                node.getDiskCapacityGb(),
                allocation.running(), allocation.vcpu(), allocation.memoryMb(),
                cpuRatio, memoryRatio, cpuWarn, memoryWarn, ipPool);
    }

    /** Per-node aggregates over currently allocated (= neither DELETED nor ERROR) VMs. */
    private Map<Long, NodeAllocation> loadAllocations() {
        Map<Long, NodeAllocation> result = new HashMap<>();
        jdbcTemplate.query("""
                select node_id,
                       count(*) filter (where status = 'RUNNING') as running,
                       coalesce(sum(vcpu), 0) as vcpu,
                       coalesce(sum(memory_mb), 0) as memory_mb
                  from vms
                 where status not in ('DELETED', 'ERROR')
                 group by node_id
                """, rs -> {
            result.put(rs.getLong("node_id"), new NodeAllocation(rs.getLong("running"),
                    rs.getLong("vcpu"), rs.getLong("memory_mb")));
        });
        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record NodeAllocation(long running, long vcpu, long memoryMb) {

        static final NodeAllocation EMPTY = new NodeAllocation(0, 0, 0);
    }
}
