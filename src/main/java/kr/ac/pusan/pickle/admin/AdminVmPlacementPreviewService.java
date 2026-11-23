package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.admin.dto.AdminApprovalImageReference;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewRequest;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.AdminVmPlacementCandidate;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementCapacityMode;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementExclusionReason;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementLiveStatus;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementResourceAxis;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementResources;
import kr.ac.pusan.pickle.admin.dto.AdminVmPlacementPreviewResponse.PlacementTotals;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageReplicaResolver;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.OsImageSelection;
import kr.ac.pusan.pickle.provisioning.NodePlacementBudget;
import kr.ac.pusan.pickle.provisioning.NodePlacementBudget.VmPlacementResources;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Observes admission budgets without changing reservations or holding a transaction during PVE reads. */
@Service
public class AdminVmPlacementPreviewService {
    private static final Logger log = LoggerFactory.getLogger(AdminVmPlacementPreviewService.class);
    private final NodeRepository nodes;
    private final OsImageRepository images;
    private final VmRepository vms;
    private final ProxmoxClient proxmox;
    private final Clock clock;
    private final Map<ProbeKey, LiveObservation> liveCache = new ConcurrentHashMap<>();
    private static final Duration LIVE_CACHE_TTL = Duration.ofSeconds(5);

    public AdminVmPlacementPreviewService(NodeRepository nodes, OsImageRepository images,
            VmRepository vms, ProxmoxClient proxmox, Clock clock) {
        this.nodes = nodes; this.images = images; this.vms = vms; this.proxmox = proxmox; this.clock = clock;
    }

    public AdminVmPlacementPreviewResponse preview(UUID orgId, AdminVmPlacementPreviewRequest request) {
        Instant observedAt = clock.instant();
        int count = request.count() == null ? 1 : request.count();
        OsImage input = images.findByPublicId(request.imageId())
                .orElseThrow(() -> new IllegalArgumentException("OS 이미지를 찾을 수 없어요."));
        List<OsImage> revision = images.findAllInDisplayOrder().stream()
                .filter(image -> image.getName().equals(input.getName()) && image.getVersion() == input.getVersion()).toList();
        OsImage canonical = OsImageSelection.canonicalOf(input, revision)
                .orElseThrow(() -> new IllegalArgumentException("OS 이미지 revision을 확인할 수 없어요."));
        boolean inputCompatible = OsImageReplicaResolver.compatible(canonical, input);
        List<Long> imageNodes = revision.stream().filter(image -> inputCompatible && image.getStatus() == CatalogStatus.ACTIVE)
                .filter(image -> OsImageReplicaResolver.compatible(canonical, image)).map(OsImage::getNodeId).distinct().toList();
        VmPlacementResources requested = new VmPlacementResources(request.vcpu(), request.memoryMb(), request.diskGb());
        List<Node> nodeRows = nodes.findAll(Sort.by("id"));
        Map<Long, LiveObservation> live = observeLive(nodeRows, imageNodes, request.nodeId());
        List<NodeObservation> observations = nodeRows.stream()
                .map(node -> observe(node, imageNodes, canonical.getMinDiskGb(), request, requested, count,
                        observedAt, live.get(node.getId()))).toList();

        // Uniform grants have independent node limits. Re-score after every planned reservation,
        // following the real reservation preference, without writing a reservation.
        int placed = 0;
        for (; placed < count; placed++) {
            NodeObservation selected = observations.stream().filter(NodeObservation::canPlanOne)
                    .min(Comparator.comparing((NodeObservation value) -> value.node.isGpuNode())
                            .thenComparing(Comparator.comparingDouble(NodeObservation::score).reversed())
                            .thenComparing(value -> value.node.getId())).orElse(null);
            if (selected == null) { break; }
            selected.planOne();
        }
        List<AdminVmPlacementCandidate> candidates = observations.stream().map(NodeObservation::response).toList();
        List<NodeObservation> eligible = observations.stream().filter(value -> value.reasons.isEmpty()).toList();
        PlacementTotals totals = new PlacementTotals(sum(eligible.stream().map(value -> value.budgetResources).toList()),
                sum(eligible.stream().map(value -> resources(value.allocated)).toList()),
                sum(eligible.stream().map(value -> value.remaining(value.allocated)).toList()),
                sum(eligible.stream().map(value -> value.remaining(value.plannedAllocated)).toList()));
        boolean selectable = observations.stream().anyMatch(value -> value.node.getStatus() == NodeStatus.ACTIVE
                && imageNodes.contains(value.node.getId()));
        return new AdminVmPlacementPreviewResponse(orgId, observedAt, request.imageId(),
                new AdminApprovalImageReference(canonical.getPublicId(), canonical.getDisplayName(), canonical.getVersion(), selectable),
                candidates, placed > 0, count, request.nodeId(), placed, count - placed, placed == count, totals);
    }

    private NodeObservation observe(Node node, List<Long> imageNodes, int minDisk,
            AdminVmPlacementPreviewRequest request, VmPlacementResources requested, int count, Instant observedAt,
            LiveObservation live) {
        var sum = vms.sumActiveByNodeId(node.getId(), VmStatus.DELETED);
        var allocated = new VmPlacementResources(sum.getVcpu(), sum.getMemoryMb(), sum.getDiskGb());
        List<PlacementExclusionReason> reasons = new ArrayList<>();
        if (node.getStatus() != NodeStatus.ACTIVE) { reasons.add(PlacementExclusionReason.INACTIVE_NODE); }
        if (!imageNodes.contains(node.getId())) { reasons.add(PlacementExclusionReason.IMAGE_UNAVAILABLE); }
        if (request.nodeId() != null && !request.nodeId().equals(node.getPublicId())) {
            reasons.add(PlacementExclusionReason.NODE_NOT_SELECTED);
        }
        if (requested.diskGb() < minDisk) { reasons.add(PlacementExclusionReason.DISK_BELOW_MINIMUM); }
        if (live.status() == PlacementLiveStatus.UNKNOWN) { reasons.add(PlacementExclusionReason.LIVE_STATUS_UNKNOWN); }
        NodePlacementBudget budget = null;
        try {
            budget = NodePlacementBudget.from(node, observedAt);
        } catch (IllegalStateException invalid) {
            reasons.add(PlacementExclusionReason.INVALID_CAPACITY);
        }
        return new NodeObservation(node, budget, allocated, requested, count, live.status(), live.observedAt(), List.copyOf(reasons));
    }

    private Map<Long, LiveObservation> observeLive(List<Node> nodeRows, List<Long> imageNodes, @Nullable UUID selectedNode) {
        Map<Long, LiveObservation> observations = new HashMap<>();
        // Spend the observation deadline on usable placement candidates first. Display order stays
        // independent so slow excluded hosts cannot starve a later healthy candidate in the queue.
        List<Node> probeRows = nodeRows.stream().sorted(Comparator.comparingInt((Node node) ->
                node.getStatus() != NodeStatus.ACTIVE || !imageNodes.contains(node.getId()) ? 2
                    : selectedNode != null && !selectedNode.equals(node.getPublicId()) ? 1 : 0)
                .thenComparing(Node::getId)).toList();
        var executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
        try {
            List<Callable<LiveObservation>> reads = probeRows.stream().<Callable<LiveObservation>>map(node -> () -> {
                ProbeKey key = new ProbeKey(node.getPublicId(), node.getApiHost(), node.getName());
                return liveCache.compute(key, (ignored, cached) -> cached != null
                        && !cached.observedAt().isAfter(clock.instant())
                        && cached.observedAt().plus(LIVE_CACHE_TTL).isAfter(clock.instant()) ? cached : probe(node));
            }).toList();
            var futures = executor.invokeAll(reads, 3, TimeUnit.SECONDS);
            for (int index = 0; index < probeRows.size(); index++) {
                LiveObservation observation = unknown();
                var future = futures.get(index);
                if (!future.isCancelled()) {
                    try { observation = future.get(); }
                    catch (ExecutionException unavailable) { log.debug("Placement observation did not complete"); }
                }
                observations.put(probeRows.get(index).getId(), observation);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            // Do not wait for cancelled I/O at the interactive response boundary. The dedicated
            // HTTP client retains its own two-second deadline and the worker count is capped.
            executor.shutdownNow();
        }
        nodeRows.forEach(node -> observations.putIfAbsent(node.getId(), unknown()));
        return observations;
    }

    private LiveObservation probe(Node node) {
        try {
            if (proxmox.nodeStatusForPlacementPreview(node.getApiHost(), node.getName()) != null) {
                return new LiveObservation(PlacementLiveStatus.REACHABLE, clock.instant());
            }
        } catch (ProxmoxApiException | IllegalStateException unavailable) {
            log.debug("Placement node observation failed for {}", node.getName());
        }
        return unknown();
    }

    private LiveObservation unknown() { return new LiveObservation(PlacementLiveStatus.UNKNOWN, clock.instant()); }
    private record ProbeKey(UUID nodeId, String apiHost, String nodeName) { }
    private record LiveObservation(PlacementLiveStatus status, Instant observedAt) { }

    private static PlacementResources resources(VmPlacementResources value) {
        return new PlacementResources(value.cpuThreads(), value.memoryMb(), value.diskGb());
    }

    private static VmPlacementResources add(VmPlacementResources left, VmPlacementResources right) {
        return new VmPlacementResources(Math.addExact(left.cpuThreads(), right.cpuThreads()),
                Math.addExact(left.memoryMb(), right.memoryMb()), Math.addExact(left.diskGb(), right.diskGb()));
    }

    private static PlacementResources sum(List<PlacementResources> values) {
        Long cpu = 0L; Long memory = 0L; Long disk = 0L;
        for (PlacementResources value : values) {
            cpu = sumAxis(cpu, value.vcpu()); memory = sumAxis(memory, value.memoryMb()); disk = sumAxis(disk, value.diskGb());
        }
        return new PlacementResources(cpu, memory, disk);
    }

    private static @Nullable Long sumAxis(@Nullable Long left, @Nullable Long right) {
        return left == null || right == null ? null : Math.addExact(left, right);
    }

    private static final class NodeObservation {
        private final Node node;
        private final @Nullable NodePlacementBudget budget;
        private final @Nullable PlacementResources budgetResources;
        private final VmPlacementResources allocated;
        private final VmPlacementResources requested;
        private final PlacementLiveStatus live;
        private final Instant liveObservedAt;
        private final List<PlacementExclusionReason> reasons;
        private final int placeable;
        private final List<PlacementResourceAxis> limiting;
        private VmPlacementResources plannedAllocated;
        private int planned;

        private NodeObservation(Node node, @Nullable NodePlacementBudget budget, VmPlacementResources allocated,
                VmPlacementResources requested, int count, PlacementLiveStatus live, Instant liveObservedAt,
                List<PlacementExclusionReason> reasons) {
            this.node = node; this.budget = budget; this.allocated = allocated; this.plannedAllocated = allocated;
            this.requested = requested; this.live = live; this.liveObservedAt = liveObservedAt; this.reasons = reasons;
            this.budgetResources = budget == null ? null : new PlacementResources(budget.limits().vcpu(),
                    budget.limits().memoryMb(), budget.limits().diskGb());
            int fits = 0;
            VmPlacementResources after = allocated;
            if (reasons.isEmpty() && budget != null) {
                while (fits < count && budget.fits(after, requested)) { after = add(after, requested); fits++; }
            }
            this.placeable = fits;
            this.limiting = reasons.isEmpty() && fits < count ? limiting(after) : List.of();
        }

        private boolean canPlanOne() { return planned < placeable; }
        private double score() { return budget == null ? Double.NEGATIVE_INFINITY : budget.score(plannedAllocated); }
        private void planOne() { plannedAllocated = add(plannedAllocated, requested); planned++; }

        private @Nullable PlacementResources remaining(VmPlacementResources used) {
            return budgetResources == null ? null : new PlacementResources(
                    subtract(budgetResources.vcpu(), used.cpuThreads()), subtract(budgetResources.memoryMb(), used.memoryMb()),
                    subtract(budgetResources.diskGb(), used.diskGb()));
        }

        private List<PlacementResourceAxis> limiting(VmPlacementResources used) {
            if (budgetResources == null) { return List.of(); }
            List<PlacementResourceAxis> result = new ArrayList<>();
            if (exceeds(budgetResources.vcpu(), used.cpuThreads(), requested.cpuThreads())) { result.add(PlacementResourceAxis.CPU); }
            if (exceeds(budgetResources.memoryMb(), used.memoryMb(), requested.memoryMb())) { result.add(PlacementResourceAxis.MEMORY); }
            if (exceeds(budgetResources.diskGb(), used.diskGb(), requested.diskGb())
                    || budget != null && !budget.reservationAware() && node.getDiskCapacityGb() != null
                        && requested.diskGb() > node.getDiskCapacityGb()) { result.add(PlacementResourceAxis.DISK); }
            return List.copyOf(result);
        }

        private AdminVmPlacementCandidate response() {
            return new AdminVmPlacementCandidate(node.getPublicId(), node.getName(), placeable > 0, node.getStatus(), live,
                    liveObservedAt, reasons.isEmpty(), reasons, budget == null ? PlacementCapacityMode.INVALID
                        : budget.reservationAware() ? PlacementCapacityMode.PREPARED : PlacementCapacityMode.LEGACY,
                    budgetResources, resources(allocated), remaining(allocated), remaining(plannedAllocated), placeable, planned, limiting);
        }

        private static @Nullable Long subtract(@Nullable Long limit, long used) { return limit == null ? null : limit - used; }
        private static boolean exceeds(@Nullable Long limit, long used, long extra) { return limit != null && (extra > limit || used > limit - extra); }
    }
}
