package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.NodeMetricPointResponse;
import kr.ac.pusan.pickle.admin.dto.NodeMetricsResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.RrdTimeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Usage time series of one node (contract op {@code getAdminNodeMetrics}),
 * read live from the hypervisor's RRD store like the per-VM series. Unlike a
 * VM, a node has no "not provisioned yet" state: a node row exists because the
 * host exists, so a host that cannot be asked is an outage and answers 503.
 *
 * <p>Deliberately not {@code @Transactional}, for the same reason as the per-VM
 * series: the hypervisor read can run to the client read timeout and must not
 * hold a pooled database connection while it does. The one database read here
 * carries its own transaction and only basic columns of the node are read
 * afterwards.
 */
@Service
public class AdminNodeMetricsService {

    private static final Logger log = LoggerFactory.getLogger(AdminNodeMetricsService.class);

    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;
    private final Clock clock;

    public AdminNodeMetricsService(NodeRepository nodeRepository, ProxmoxClient proxmoxClient,
            Clock clock) {
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.clock = clock;
    }

    public NodeMetricsResponse metrics(long nodeId, RrdTimeframe timeframe) {
        Node node = nodeRepository.findById(nodeId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                "해당 노드가 존재하지 않습니다."));
        List<NodeMetricPointResponse> points;
        try {
            points = proxmoxClient.nodeRrdData(node.getApiHost(), node.getName(), timeframe)
                    .stream()
                    // A row with no timestamp has no place on the axis.
                    .filter(sample -> sample.time() != null)
                    .map(NodeMetricPointResponse::from)
                    .toList();
        } catch (ProxmoxApiException | IllegalStateException e) {
            // An unconfigured API token refuses before the request leaves, which
            // is the same "PVE cannot be asked" as a transport failure.
            log.warn("Node {} usage read failed: {}", node.getName(), e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.METRICS_UNAVAILABLE,
                    "사용량 데이터를 불러오지 못했습니다", "잠시 후 다시 시도해 주세요.");
        }
        return new NodeMetricsResponse(timeframe.name(), clock.instant(), points);
    }
}
