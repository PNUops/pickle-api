package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.CloneImagePin;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageReplicaResolver;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.OsImageSelection;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes approval-time placement and exact clone-source reservation. */
@Service
public class VmCloneReservationService {

    private final OsImageRepository images;
    private final NodeRepository nodes;
    private final VmRepository vms;

    public VmCloneReservationService(OsImageRepository images, NodeRepository nodes,
            VmRepository vms) {
        this.images = images;
        this.nodes = nodes;
        this.vms = vms;
    }

    @Transactional
    public Reservation reserve(UUID imagePublicId, @Nullable UUID forcedNodePublicId,
            NodePlacementBudget.VmPlacementResources requested) {
        return reserve(imagePublicId, forcedNodePublicId, requested, Set.of());
    }

    /**
     * Places on the best node that fits, skipping {@code excludedNodeIds}.
     * The exclusion is the caller's own limit, not a capacity fact, so the two
     * failures stay apart: no node fits at all is {@link NoCapacityException},
     * while some node would fit but every such node is excluded is
     * {@link ExcludedNodesOnlyException}, which a caller can wait out.
     *
     * <p>Exclusion applies after the ranking, so GPU nodes stay last in
     * preference but are used once every earlier node is excluded: a GPU
     * node is not left idle while CPU nodes are at the caller's limit.</p>
     */
    @Transactional
    public Reservation reserve(UUID imagePublicId, @Nullable UUID forcedNodePublicId,
            NodePlacementBudget.VmPlacementResources requested, Set<Long> excludedNodeIds) {
        OsImage preRead = images.findByPublicId(imagePublicId)
                .orElseThrow(() -> new IllegalStateException("승인할 OS 이미지가 존재하지 않습니다."));
        List<OsImage> revision = images.findRevisionForUpdate(preRead.getName(), preRead.getVersion());
        OsImage granted = revision.stream().filter(row -> Objects.equals(row.getId(), preRead.getId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("승인할 OS 이미지가 변경되었습니다."));
        OsImage canonical = OsImageSelection.canonicalOf(granted, revision)
                .orElseThrow(() -> new IllegalStateException("OS 이미지 revision을 확인할 수 없습니다."));
        if (!OsImageReplicaResolver.compatible(canonical, granted)) {
            throw new IllegalStateException("승인할 OS 이미지 revision이 일치하지 않습니다.");
        }

        List<OsImage> replicas = revision.stream()
                .filter(row -> row.getStatus() == CatalogStatus.ACTIVE)
                .filter(row -> OsImageReplicaResolver.compatible(canonical, row))
                .toList();
        List<Long> nodeIds = replicas.stream().map(OsImage::getNodeId).distinct().sorted().toList();
        if (nodeIds.isEmpty()) {
            throw noCapacity(canonical);
        }
        List<Node> lockedNodes = nodes.findAllByIdForUpdate(nodeIds);
        Instant observedAt = Instant.now();
        List<Candidate> candidates = lockedNodes.stream()
                .filter(node -> node.getStatus() == NodeStatus.ACTIVE)
                .filter(node -> forcedNodePublicId == null || forcedNodePublicId.equals(node.getPublicId()))
                .map(node -> candidate(node, replicas, requested, observedAt))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Candidate value) -> value.node().isGpuNode())
                        .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())
                        .thenComparing(value -> value.node().getId()))
                .toList();
        if (candidates.isEmpty()) {
            throw noCapacity(canonical);
        }
        Candidate selected = candidates.stream()
                .filter(value -> !excludedNodeIds.contains(value.node().getId()))
                .findFirst().orElseThrow(ExcludedNodesOnlyException::new);
        CloneImagePin pin = CloneImagePin.from(selected.replica());
        return new Reservation(canonical, selected.replica(), selected.node(), pin);
    }

    private Candidate candidate(Node node, List<OsImage> replicas,
            NodePlacementBudget.VmPlacementResources requested, Instant observedAt) {
        OsImage replica = replicas.stream().filter(row -> Objects.equals(row.getNodeId(), node.getId()))
                .min(Comparator.comparing(OsImage::getId)).orElse(null);
        if (replica == null) {
            return null;
        }
        VmRepository.AllocatedCapacity sum = vms.sumActiveByNodeId(node.getId(),
                kr.ac.pusan.pickle.vm.VmStatus.DELETED);
        NodePlacementBudget.VmPlacementResources allocated = new NodePlacementBudget.VmPlacementResources(
                sum.getVcpu(), sum.getMemoryMb(), sum.getDiskGb());
        NodePlacementBudget budget = NodePlacementBudget.from(node, observedAt);
        return budget.fits(allocated, requested)
                ? new Candidate(node, replica, budget.score(allocated)) : null;
    }

    private static NoCapacityException noCapacity(OsImage image) {
        return new NoCapacityException("요청 사양과 OS 이미지 " + image.getName()
                + "을(를) 수용할 수 있는 노드가 없습니다.");
    }

    private record Candidate(Node node, OsImage replica, double score) {
    }

    public record Reservation(OsImage canonical, OsImage replica, Node node, CloneImagePin pin) {
    }

    public static final class NoCapacityException extends RuntimeException {

        private NoCapacityException(String message) {
            super(message);
        }
    }

    /** Some node fits, but every node that does was excluded by the caller. */
    public static final class ExcludedNodesOnlyException extends RuntimeException {

        private ExcludedNodesOnlyException() {
            super("every node that fits was excluded");
        }
    }
}
