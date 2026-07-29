package kr.ac.pusan.pickle.provisioning;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-1 node placement: among ACTIVE nodes that host an
 * ACTIVE OS image of the same name, pick the one with the best headroom
 * score; an admin-forced node (approval form {@code nodeId}) always wins.
 *
 * <p>Scoring is deliberately simple for the single-node cluster of today —
 * the seam is what matters. Capacity is computed from DB intent (active vm
 * rows), not live Proxmox numbers: {@code score = 0.6 * free_mem_ratio +
 * 0.4 * (1 - vcpu_overcommit)}. The free-disk term of the plan is omitted
 * until nodes carry a disk-capacity column. Memory is a hard filter (no
 * memory overcommit for placement); vCPU may overcommit and only lowers
 * the score.</p>
 */
@Service
public class NodePlacementService {

    private static final Logger log = LoggerFactory.getLogger(NodePlacementService.class);

    private final NodeRepository nodeRepository;
    private final OsImageRepository imageRepository;
    private final VmRepository vmRepository;

    public NodePlacementService(NodeRepository nodeRepository,
            OsImageRepository imageRepository, VmRepository vmRepository) {
        this.nodeRepository = nodeRepository;
        this.imageRepository = imageRepository;
        this.vmRepository = vmRepository;
    }

    /**
     * Picks the node for the VM.
     *
     * @param forcedNodeId admin-chosen node from the approval, or null
     * @throws IllegalStateException when no node can take the VM (a permanent
     *                               pipeline failure — the VM errors out)
     */
    @Transactional(readOnly = true)
    public Node place(Vm vm, OsImage image, Long forcedNodeId) {
        if (forcedNodeId != null) {
            Node node = nodeRepository.findById(forcedNodeId)
                    .filter(n -> n.getStatus() == NodeStatus.ACTIVE)
                    .orElseThrow(() -> new IllegalStateException(
                            "관리자 지정 노드 " + forcedNodeId + "를 사용할 수 없습니다"));
            // Backstop for approval-time validation: the image can be
            // deactivated between approval and provisioning. Fail here — the
            // same IllegalStateException the no-candidate path throws, so the
            // pipeline errors cleanly at the place step — instead of proceeding
            // to a clone that would fail on a node without the image.
            if (!imageRepository.existsByNameAndNodeIdAndStatus(
                    image.getName(), node.getId(), TemplateStatus.ACTIVE)) {
                throw new IllegalStateException("관리자 지정 노드 " + node.getId()
                        + "에 템플릿 " + image.getName() + "이(가) 없습니다");
            }
            log.info("placement for vm {}: admin-forced node {} ({})", vm.getId(), node.getId(),
                    node.getName());
            return node;
        }
        // Nodes hosting an ACTIVE image of the same name (image rows are
        // per-node; a multi-node cluster clones the image under one name).
        Set<Long> imageNodeIds = imageRepository
                .findByStatusOrderByIdAsc(TemplateStatus.ACTIVE).stream()
                .filter(candidate -> candidate.getName().equals(image.getName()))
                .map(OsImage::getNodeId)
                .collect(Collectors.toSet());
        return nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE).stream()
                .filter(node -> imageNodeIds.contains(node.getId()))
                .filter(node -> hasMemoryHeadroom(node, vm))
                .max(Comparator.comparingDouble(this::score))
                .map(node -> {
                    log.info("placement for vm {}: node {} ({}) score {}", vm.getId(),
                            node.getId(), node.getName(), score(node));
                    return node;
                })
                .orElseThrow(() -> new IllegalStateException(
                        "요청 사양을 수용할 수 있는 노드가 없습니다 (템플릿 " + image.getName() + ")"));
    }

    private boolean hasMemoryHeadroom(Node node, Vm vm) {
        VmRepository.AllocatedCapacity allocated =
                vmRepository.sumActiveByNodeId(node.getId(), VmStatus.DELETED);
        return allocated.getMemoryMb() + vm.getMemoryMb() <= node.getMemoryMb();
    }

    private double score(Node node) {
        VmRepository.AllocatedCapacity allocated =
                vmRepository.sumActiveByNodeId(node.getId(), VmStatus.DELETED);
        double freeMemRatio = Math.max(0.0,
                1.0 - (double) allocated.getMemoryMb() / node.getMemoryMb());
        double vcpuOvercommit = (double) allocated.getVcpu() / node.getCpuThreads();
        return 0.6 * freeMemRatio + 0.4 * (1.0 - vcpuOvercommit);
    }
}
