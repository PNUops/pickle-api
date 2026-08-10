package kr.ac.pusan.pickle.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.RrdTimeframe;
import kr.ac.pusan.pickle.proxmox.dto.VmRrdSample;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.vm.dto.VmMetricPointResponse;
import kr.ac.pusan.pickle.vm.dto.VmMetricsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The three answers the usage read can give, none of which is reachable
 * against the test database: a VM with no guest behind it must not produce a
 * Proxmox call at all, a live one must map the RRD rows gaps and all, and a
 * hypervisor that refuses must become a 503 rather than an empty chart.
 */
@ExtendWith(MockitoExtension.class)
class VmMetricsServiceTest {

    private static final AuthenticatedUser ACTOR =
            new AuthenticatedUser(7L, "metrics@pusan.ac.kr", UserRole.USER, null);

    @Mock
    private VmAccessService vmAccessService;
    @Mock
    private NodeRepository nodeRepository;
    @Mock
    private ProxmoxClient proxmoxClient;

    @Test
    void aVmWithNoGuestBehindItIsNotAnErrorAndIsNotAskedAbout() {
        Vm vm = mock(Vm.class);
        when(vm.getProxmoxVmid()).thenReturn(null);
        grantVisible(vm);

        VmMetricsResponse response = service().metrics(ACTOR, 1L, RrdTimeframe.HOUR);

        assertThat(response.available()).isFalse();
        assertThat(response.unavailableReason()).isEqualTo("NOT_PROVISIONED");
        assertThat(response.points()).isEmpty();
        assertThat(response.timeframe()).isEqualTo("HOUR");
        verifyNoInteractions(proxmoxClient, nodeRepository);
    }

    @Test
    void aDeletedVmAnswersTheSameWayItsGuestIsGone() {
        Vm vm = mock(Vm.class);
        when(vm.getProxmoxVmid()).thenReturn(100_001);
        when(vm.getStatus()).thenReturn(VmStatus.DELETED);
        grantVisible(vm);

        VmMetricsResponse response = service().metrics(ACTOR, 1L, RrdTimeframe.DAY);

        assertThat(response.available()).isFalse();
        assertThat(response.unavailableReason()).isEqualTo("NOT_PROVISIONED");
        verifyNoInteractions(proxmoxClient);
    }

    @Test
    void rrdRowsBecomePointsAndTheirGapsSurvive() {
        Vm vm = runningVm();
        grantVisible(vm);
        Node node = node();
        when(nodeRepository.findById(3L)).thenReturn(Optional.of(node));
        when(proxmoxClient.vmRrdData(anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new VmRrdSample(1_786_335_540L, 0.0125, 2.0, 1.5e9, 2.5e9, 4.0e9,
                                1024.5, 2048.5, 10.0, 20.0),
                        // A stopped interval: PVE omits every measured key.
                        new VmRrdSample(1_786_335_600L, null, 2.0, null, null, 4.0e9,
                                null, null, null, null)));

        VmMetricsResponse response = service().metrics(ACTOR, 1L, RrdTimeframe.HOUR);

        assertThat(response.available()).isTrue();
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.points()).hasSize(2);
        VmMetricPointResponse live = response.points().getFirst();
        assertThat(live.time()).isEqualTo(Instant.ofEpochSecond(1_786_335_540L));
        assertThat(live.cpu()).isEqualTo(0.0125);
        assertThat(live.memBytes()).isEqualTo(1_500_000_000L);
        assertThat(live.memHostBytes()).isEqualTo(2_500_000_000L);
        assertThat(live.maxmemBytes()).isEqualTo(4_000_000_000L);
        assertThat(live.netinBps()).isEqualTo(1024.5);
        assertThat(live.diskWriteBps()).isEqualTo(20.0);
        VmMetricPointResponse gap = response.points().getLast();
        assertThat(gap.time()).isEqualTo(Instant.ofEpochSecond(1_786_335_600L));
        assertThat(gap.cpu()).isNull();
        assertThat(gap.memBytes()).isNull();
        assertThat(gap.netinBps()).isNull();
        assertThat(gap.maxmemBytes()).isEqualTo(4_000_000_000L);
    }

    @Test
    void aHypervisorThatRefusesIsA503RatherThanAnEmptyChart() {
        Vm vm = runningVm();
        grantVisible(vm);
        Node node = node();
        when(nodeRepository.findById(3L)).thenReturn(Optional.of(node));
        when(proxmoxClient.vmRrdData(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new ProxmoxApiException(596, "no such resource", "GET rrddata"));

        assertThatThrownBy(() -> service().metrics(ACTOR, 1L, RrdTimeframe.HOUR))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.METRICS_UNAVAILABLE);
                });
    }

    private Vm runningVm() {
        Vm vm = mock(Vm.class);
        when(vm.getProxmoxVmid()).thenReturn(100_001);
        when(vm.getStatus()).thenReturn(VmStatus.RUNNING);
        when(vm.getNodeId()).thenReturn(3L);
        return vm;
    }

    private void grantVisible(Vm vm) {
        when(vmAccessService.of(ACTOR, 1L))
                .thenReturn(new VmAccess(vm, ResourceRole.VIEWER, true, false));
    }

    private static Node node() {
        Node node = mock(Node.class);
        when(node.getApiHost()).thenReturn("https://pve-test:8006");
        when(node.getName()).thenReturn("pve-test");
        return node;
    }

    private VmMetricsService service() {
        return new VmMetricsService(vmAccessService, nodeRepository, proxmoxClient,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
    }
}
