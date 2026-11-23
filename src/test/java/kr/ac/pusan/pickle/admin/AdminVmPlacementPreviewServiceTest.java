package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewRequest;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementExclusionReason;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementLiveStatus;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementResourceAxis;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.NodeStatusInfo;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class AdminVmPlacementPreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private final NodeRepository nodes = mock(NodeRepository.class);
    private final OsImageRepository images = mock(OsImageRepository.class);
    private final VmRepository vms = mock(VmRepository.class);
    private final ProxmoxClient proxmox = mock(ProxmoxClient.class);
    private final AdminVmPlacementPreviewService service = new AdminVmPlacementPreviewService(nodes, images, vms,
            proxmox, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID orgId = UUID.randomUUID();
    private Node first;
    private Node second;
    private OsImage canonical;
    private OsImage replica;

    @BeforeEach
    void setup() {
        first = node(1, "first", 24, 5);
        second = node(2, "second", 32, 3);
        canonical = image(1, first);
        replica = image(2, second);
        when(nodes.findAll(any(Sort.class))).thenReturn(List.of(first, second));
        when(images.findByPublicId(canonical.getPublicId())).thenReturn(java.util.Optional.of(canonical));
        when(images.findAllInDisplayOrder()).thenReturn(List.of(canonical, replica));
        for (Node node : List.of(first, second)) {
            when(vms.sumActiveByNodeId(node.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 0, 0));
            when(proxmox.nodeStatusForPlacementPreview(node.getApiHost(), node.getName())).thenReturn(new NodeStatusInfo(null, null, null));
        }
    }

    @Test
    void defaultsToOneAndUsesPreparedBudgetsRatherThanPhysicalThreads() {
        var response = preview(1, 1024, 10, null, null);
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.fitsAllNow()).isTrue();
        assertThat(response.totals().budget().vcpu()).isEqualTo(56);
        assertThat(response.totals().remaining().vcpu()).isEqualTo(56);
        assertThat(response.totals().after().vcpu()).isEqualTo(55);
        assertThat(response.candidates()).extracting(value -> value.budget().vcpu()).containsExactly(19L, 37L);
    }

    @Test
    void distributesUniformGrantsAndReportsCpuShortfallAtTheExactBoundary() {
        var response = preview(2, 1024, 10, 30, null);
        assertThat(response.placeableCount()).isEqualTo(27);
        assertThat(response.shortfallCount()).isEqualTo(3);
        assertThat(response.fitsAnyOneNow()).isTrue();
        assertThat(response.fitsAllNow()).isFalse();
        assertThat(response.candidates()).extracting(value -> value.plannedCount()).containsExactly(9, 18);
        assertThat(response.candidates()).allSatisfy(value -> assertThat(value.limitingResources()).containsExactly(PlacementResourceAxis.CPU));
        assertThat(response.totals().after().vcpu()).isEqualTo(2);
    }

    @Test
    void subtractsEveryNonDeletedVmAllocationFromTheNodeOnce() {
        when(vms.sumActiveByNodeId(first.getId(), VmStatus.DELETED)).thenReturn(allocated(4, 4096, 40));
        when(vms.sumActiveByNodeId(second.getId(), VmStatus.DELETED)).thenReturn(allocated(6, 6144, 60));
        var response = preview(1, 1024, 10, 30, null);
        assertThat(response.totals().allocated().vcpu()).isEqualTo(10);
        assertThat(response.totals().remaining().vcpu()).isEqualTo(46);
        assertThat(response.totals().after().vcpu()).isEqualTo(16);
        verify(vms).sumActiveByNodeId(first.getId(), VmStatus.DELETED);
        verify(vms).sumActiveByNodeId(second.getId(), VmStatus.DELETED);
    }

    @Test
    void summedMemoryCannotHideThatNoIndividualNodeFits() {
        when(vms.sumActiveByNodeId(first.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 37344, 0));
        when(vms.sumActiveByNodeId(second.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 37344, 0));
        var response = preview(1, 30000, 10, 1, null);
        assertThat(response.totals().remaining().memoryMb()).isEqualTo(40000);
        assertThat(response.placeableCount()).isZero();
        assertThat(response.candidates()).allSatisfy(value -> assertThat(value.limitingResources()).containsExactly(PlacementResourceAxis.MEMORY));
    }

    @Test
    void diskBudgetLimitsPreparedNodes() {
        when(vms.sumActiveByNodeId(first.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 0, 880));
        when(vms.sumActiveByNodeId(second.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 0, 890));
        var response = preview(1, 1024, 10, 4, null);
        assertThat(response.placeableCount()).isEqualTo(3);
        assertThat(response.shortfallCount()).isEqualTo(1);
        assertThat(response.candidates()).allSatisfy(value -> assertThat(value.limitingResources()).containsExactly(PlacementResourceAxis.DISK));
    }

    @Test
    void forcedNodeDoesNotBorrowAnotherNodesBudget() {
        var response = preview(1, 1024, 10, 30, first.getPublicId());
        assertThat(response.nodeId()).isEqualTo(first.getPublicId());
        assertThat(response.placeableCount()).isEqualTo(19);
        assertThat(response.shortfallCount()).isEqualTo(11);
        assertThat(response.totals().budget().vcpu()).isEqualTo(19);
        assertThat(response.candidates().get(1).exclusionReasons()).containsExactly(PlacementExclusionReason.NODE_NOT_SELECTED);
    }

    @Test
    void inactiveManagementStatusAndActualConnectivityRemainSeparate() {
        first.setStatus(NodeStatus.OFFLINE);
        second.setStatus(NodeStatus.MAINTENANCE);
        var response = preview(1, 1024, 10, 1, null);
        assertThat(response.placeableCount()).isZero();
        assertThat(response.totals().budget().vcpu()).isZero();
        assertThat(response.candidates()).hasSize(2).allSatisfy(value -> {
            assertThat(value.liveStatus()).isEqualTo(PlacementLiveStatus.REACHABLE);
            assertThat(value.eligible()).isFalse();
            assertThat(value.exclusionReasons()).contains(PlacementExclusionReason.INACTIVE_NODE);
        });
    }

    @Test
    void oneProbeFailureDoesNotHideTheOtherNodesCapacityOrAssertAPowerState() {
        when(proxmox.nodeStatusForPlacementPreview(second.getApiHost(), second.getName()))
                .thenThrow(new ProxmoxApiException("unreachable", new java.io.IOException()));
        var response = preview(1, 1024, 10, 30, null);
        assertThat(response.placeableCount()).isEqualTo(19);
        assertThat(response.totals().remaining().vcpu()).isEqualTo(19);
        var failed = response.candidates().get(1);
        assertThat(failed.status()).isEqualTo(NodeStatus.ACTIVE);
        assertThat(failed.liveStatus()).isEqualTo(PlacementLiveStatus.UNKNOWN);
        assertThat(failed.liveObservedAt()).isEqualTo(NOW);
        assertThat(failed.exclusionReasons()).containsExactly(PlacementExclusionReason.LIVE_STATUS_UNKNOWN);
    }

    @Test
    void nullProbeDataIsUnknown() {
        when(proxmox.nodeStatusForPlacementPreview(first.getApiHost(), first.getName())).thenReturn(null);
        var response = preview(1, 1024, 10, 40, null);
        assertThat(response.placeableCount()).isEqualTo(37);
        assertThat(response.candidates().getFirst().liveStatus()).isEqualTo(PlacementLiveStatus.UNKNOWN);
    }

    @Test
    void slowNodesDoNotHoldTheResponseOrHideAFastNode() {
        when(proxmox.nodeStatusForPlacementPreview(second.getApiHost(), second.getName())).thenAnswer(ignored -> {
            Thread.sleep(10000);
            return new NodeStatusInfo(null, null, null);
        });
        var response = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> preview(1, 1024, 10, 30, null));
        assertThat(response.placeableCount()).isEqualTo(19);
        assertThat(response.candidates().get(1).liveStatus()).isEqualTo(PlacementLiveStatus.UNKNOWN);
    }

    @Test
    void slowExcludedHostsCannotStarveALaterHealthyPlacementCandidate() {
        var excluded = java.util.stream.LongStream.rangeClosed(1, 4).mapToObj(id -> node(id, "excluded-" + id, 24, 5)).toList();
        excluded.forEach(value -> value.setStatus(NodeStatus.OFFLINE));
        Node healthy = node(5, "healthy", 24, 5);
        List<Node> rows = new java.util.ArrayList<>(excluded); rows.add(healthy);
        canonical = image(5, healthy);
        when(nodes.findAll(any(Sort.class))).thenReturn(rows);
        when(images.findByPublicId(canonical.getPublicId())).thenReturn(java.util.Optional.of(canonical));
        when(images.findAllInDisplayOrder()).thenReturn(List.of(canonical));
        for (Node node : rows) {
            when(vms.sumActiveByNodeId(node.getId(), VmStatus.DELETED)).thenReturn(allocated(0, 0, 0));
            when(proxmox.nodeStatusForPlacementPreview(node.getApiHost(), node.getName())).thenAnswer(ignored -> {
                Thread.sleep(node == healthy ? 1200 : 2000);
                return new NodeStatusInfo(null, null, null);
            });
        }
        var response = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> preview(1, 1024, 10, 1, null));
        assertThat(response.placeableCount()).isEqualTo(1);
        assertThat(response.candidates()).extracting(value -> value.nodeName())
                .containsExactly("excluded-1", "excluded-2", "excluded-3", "excluded-4", "healthy");
        assertThat(response.candidates().get(4).liveStatus()).isEqualTo(PlacementLiveStatus.REACHABLE);
        assertThat(response.candidates().get(4).eligible()).isTrue();
    }

    @Test
    void recentConnectivityObservationIsReusedWithoutMovingItsTimestamp() {
        preview(1, 1024, 10, 1, null);
        var response = preview(1, 1024, 10, 2, null);
        verify(proxmox, times(1)).nodeStatusForPlacementPreview(first.getApiHost(), first.getName());
        verify(proxmox, times(1)).nodeStatusForPlacementPreview(second.getApiHost(), second.getName());
        assertThat(response.candidates()).allSatisfy(value -> assertThat(value.liveObservedAt()).isEqualTo(NOW));
    }

    @Test
    void incompatibleOrMissingReplicaExcludesCapacityEvenWhenReachable() {
        ReflectionTestUtils.setField(replica, "sshUsername", "incompatible");
        var response = preview(1, 1024, 10, 30, null);
        assertThat(response.placeableCount()).isEqualTo(19);
        assertThat(response.candidates().get(1).exclusionReasons()).containsExactly(PlacementExclusionReason.IMAGE_UNAVAILABLE);
    }

    @Test
    void invalidMetadataFailsClosedForOneNodeWithoutFallingBackToLegacy() {
        ReflectionTestUtils.setField(first, "labels", Map.of("placement_capacity", Map.of("schema_version", 99)));
        var response = preview(1, 1024, 10, 40, null);
        assertThat(response.placeableCount()).isEqualTo(37);
        var invalid = response.candidates().getFirst();
        assertThat(invalid.budget()).isNull();
        assertThat(invalid.remaining()).isNull();
        assertThat(invalid.exclusionReasons()).containsExactly(PlacementExclusionReason.INVALID_CAPACITY);
    }

    @Test
    void legacyCpuAndDiskStayUnboundedWhileMemoryAndOneDiskStillConstrainFit() {
        ReflectionTestUtils.setField(first, "labels", null);
        var response = preview(100, 1024, 10, 2, first.getPublicId());
        assertThat(response.placeableCount()).isEqualTo(2);
        assertThat(response.totals().budget().vcpu()).isNull();
        assertThat(response.totals().remaining().vcpu()).isNull();
        assertThat(response.totals().remaining().diskGb()).isNull();
        assertThat(response.totals().remaining().memoryMb()).isEqualTo(57344);
        var oversized = preview(1, 1024, 1001, 1, first.getPublicId());
        assertThat(oversized.placeableCount()).isZero();
        assertThat(oversized.candidates().getFirst().limitingResources()).containsExactly(PlacementResourceAxis.DISK);
    }

    @Test
    void diskBelowTheImageMinimumIsNotPresentedAsFitting() {
        var response = preview(1, 1024, 9, 1, null);
        assertThat(response.placeableCount()).isZero();
        assertThat(response.candidates()).allSatisfy(value -> assertThat(value.exclusionReasons())
                .contains(PlacementExclusionReason.DISK_BELOW_MINIMUM));
    }

    private AdminVmPlacementPreviewResponse preview(int cpu, int memory, int disk, Integer count, UUID nodeId) {
        return service.preview(orgId, new AdminVmPlacementPreviewRequest(orgId, canonical.getPublicId(), cpu, memory,
                disk, null, count, nodeId));
    }

    private static Node node(long id, String name, int threads, int external) {
        Node node = BeanUtils.instantiateClass(Node.class);
        ReflectionTestUtils.setField(node, "id", id); ReflectionTestUtils.setField(node, "name", name);
        ReflectionTestUtils.setField(node, "apiHost", "https://example.invalid:8006");
        ReflectionTestUtils.setField(node, "cpuThreads", threads); ReflectionTestUtils.setField(node, "memoryMb", 57344);
        ReflectionTestUtils.setField(node, "diskCapacityGb", 1000L);
        ReflectionTestUtils.setField(node, "labels", Map.of("placement_capacity", Map.of(
                "schema_version", 2, "measured_at", NOW.minusSeconds(60).toString(),
                "physical", Map.of("cpu_threads", threads, "memory_mb", 65536, "disk_gb", 1000),
                "reserved", Map.of("cpu_threads", 12, "memory_mb", 8192, "disk_gb", 100),
                "allocatable", Map.of("cpu_threads", (threads - 12) * 2 - external, "memory_mb", 57344, "disk_gb", 900),
                "cpu_policy", Map.of("allocation_ratio", 2, "committed_vcpu", external))));
        return node;
    }

    private static OsImage image(long id, Node node) {
        var image = new OsImage("example-os", "Example OS", "ubuntu", "26.04", "ubuntu", 1000,
                node.getId(), 1, 10, CatalogStatus.ACTIVE, null);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    private static VmRepository.AllocatedCapacity allocated(long cpu, long memory, long disk) {
        return new VmRepository.AllocatedCapacity() {
            public long getVcpu() { return cpu; }
            public long getMemoryMb() { return memory; }
            public long getDiskGb() { return disk; }
        };
    }
}
