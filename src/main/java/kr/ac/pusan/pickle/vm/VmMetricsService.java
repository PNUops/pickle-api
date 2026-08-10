package kr.ac.pusan.pickle.vm;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.RrdTimeframe;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.VmMetricPointResponse;
import kr.ac.pusan.pickle.vm.dto.VmMetricsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Usage time series of one VM (contract op {@code getVmMetrics}), read live
 * from the hypervisor's RRD store. Visibility is the VM's access list at the
 * VIEWER rung, so a non-member gets the same 404 mask as every other read.
 *
 * <p>Nothing is stored on this side: PVE already keeps the series, and a copy
 * would only be a second answer to the same question. That makes the
 * hypervisor a hard dependency of the read — when it cannot be asked, the
 * answer is 503 rather than an empty chart that reads as "the VM was idle".
 * The one case that is genuinely not an error is a VM with no guest behind it
 * yet (or a deleted one): there is nothing to ask about, so it answers 200 with
 * {@code available: false} and never touches Proxmox.
 *
 * <p>Deliberately not {@code @Transactional}: the hypervisor read can sit for
 * the whole client read timeout, and holding a pooled connection across it lets
 * a stalled PVE plus a polling chart drain the pool out from under unrelated
 * endpoints. Both database reads below carry their own transaction (the access
 * lookup and the repository call), and only basic columns of the entities they
 * return are touched afterwards, so nothing lazy is left outside one.
 */
@Service
public class VmMetricsService {

    private static final Logger log = LoggerFactory.getLogger(VmMetricsService.class);

    /** {@code unavailableReason} for a VM that has no hypervisor guest to read. */
    private static final String NOT_PROVISIONED = "NOT_PROVISIONED";

    private final VmAccessService vmAccessService;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;
    private final Clock clock;

    public VmMetricsService(VmAccessService vmAccessService, NodeRepository nodeRepository,
            ProxmoxClient proxmoxClient, Clock clock) {
        this.vmAccessService = vmAccessService;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.clock = clock;
    }

    public VmMetricsResponse metrics(AuthenticatedUser actor, UUID vmId, RrdTimeframe timeframe) {
        Vm vm = vmAccessService.of(actor, vmId).requireVisible();
        if (vm.getProxmoxVmid() == null || vm.getStatus() == VmStatus.DELETED) {
            return new VmMetricsResponse(timeframe.name(), clock.instant(), false, NOT_PROVISIONED,
                    List.of());
        }
        Node node = nodeRepository.findById(vm.getNodeId()).orElseThrow(
                VmMetricsService::metricsUnavailable);
        List<VmMetricPointResponse> points;
        try {
            points = proxmoxClient
                    .vmRrdData(node.getApiHost(), node.getName(), vm.getProxmoxVmid(), timeframe)
                    .stream()
                    // A row with no timestamp has no place on the axis.
                    .filter(sample -> sample.time() != null)
                    .map(VmMetricPointResponse::from)
                    .toList();
        } catch (ProxmoxApiException | IllegalStateException e) {
            // The message carries the PVE reason and never the token (client layer).
            // An unconfigured token refuses the same way from the caller's side:
            // PVE cannot be asked, so the answer is the outage, not a 500.
            log.warn("VM {} usage read failed on node {}: {}", vmId, node.getName(),
                    e.getMessage());
            throw metricsUnavailable();
        }
        return new VmMetricsResponse(timeframe.name(), clock.instant(), true, null, points);
    }

    private static ApiException metricsUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.METRICS_UNAVAILABLE,
                "사용량 데이터를 불러오지 못했습니다", "잠시 후 다시 시도해 주세요.");
    }
}
