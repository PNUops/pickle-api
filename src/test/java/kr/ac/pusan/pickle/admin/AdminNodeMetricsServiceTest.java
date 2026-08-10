package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.NodeMetricsResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.RrdTimeframe;
import kr.ac.pusan.pickle.proxmox.dto.NodeRrdSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The node series answers the same way the per-VM one does: RRD rows map gaps
 * and all, a row with no timestamp is dropped because it cannot be placed on
 * the axis, and every refusal the client can raise — over the wire or before
 * the request leaves, when no API token is configured — becomes the 503 the
 * contract describes rather than a 500.
 */
@ExtendWith(MockitoExtension.class)
class AdminNodeMetricsServiceTest {

    private static final UUID NODE_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID UNKNOWN_NODE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000404");

    @Mock
    private NodeRepository nodeRepository;
    @Mock
    private ProxmoxClient proxmoxClient;

    @Test
    void rrdRowsBecomePointsAndTheTimelessOnesAreDropped() {
        Node node = node();
        when(nodeRepository.findByPublicId(NODE_ID)).thenReturn(Optional.of(node));
        when(proxmoxClient.nodeRrdData(anyString(), anyString(), any()))
                .thenReturn(List.of(
                        new NodeRrdSample(null, 0.5, 32.0, 0.01, 1.0, 1.0e10, 5.0e9, 4.0e9,
                                0.0, 0.0, 1.0e11, 2.0e10, 10.0, 20.0),
                        new NodeRrdSample(1_786_335_600L, 0.25, 32.0, 0.02, 2.0, 1.0e10, 6.0e9,
                                4.0e9, 0.0, 0.0, 1.0e11, 2.0e10, 10.0, null)));

        NodeMetricsResponse response = new AdminNodeMetricsService(nodeRepository, proxmoxClient,
                clock()).metrics(NODE_ID, RrdTimeframe.HOUR);

        assertThat(response.timeframe()).isEqualTo("HOUR");
        assertThat(response.points()).hasSize(1);
        assertThat(response.points().getFirst().time())
                .isEqualTo(Instant.ofEpochSecond(1_786_335_600L));
        assertThat(response.points().getFirst().memUsedBytes()).isEqualTo(6_000_000_000L);
        assertThat(response.points().getFirst().netoutBps()).isNull();
    }

    @Test
    void aHypervisorThatRefusesIsA503() {
        Node node = node();
        when(nodeRepository.findByPublicId(NODE_ID)).thenReturn(Optional.of(node));
        when(proxmoxClient.nodeRrdData(anyString(), anyString(), any()))
                .thenThrow(new ProxmoxApiException(596, "no such resource", "GET rrddata"));

        assertMetricsUnavailable();
    }

    @Test
    void anUnconfiguredApiTokenIsTheSameOutageAsARefusal() {
        Node node = node();
        when(nodeRepository.findByPublicId(NODE_ID)).thenReturn(Optional.of(node));
        // The client refuses before the request leaves when the deployment
        // carries no PVE token — a state the configuration explicitly allows.
        when(proxmoxClient.nodeRrdData(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Proxmox API token is not configured"));

        assertMetricsUnavailable();
    }

    @Test
    void anUnknownNodeIsA404AndIsNeverAskedAbout() {
        when(nodeRepository.findByPublicId(UNKNOWN_NODE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().metrics(UNKNOWN_NODE_ID, RrdTimeframe.HOUR))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
                });
    }

    private void assertMetricsUnavailable() {
        assertThatThrownBy(() -> service().metrics(NODE_ID, RrdTimeframe.HOUR))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.METRICS_UNAVAILABLE);
                });
    }

    private static Node node() {
        Node node = mock(Node.class);
        when(node.getApiHost()).thenReturn("https://pve-test:8006");
        when(node.getName()).thenReturn("pve-test");
        return node;
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    }

    private AdminNodeMetricsService service() {
        return new AdminNodeMetricsService(nodeRepository, proxmoxClient, clock());
    }
}
