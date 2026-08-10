package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.ResourceTotalsResponse;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared org-headroom aggregation: allocated totals of an org's active
 * (non-deleted) VMs against platform ACTIVE-node capacity, with the
 * operator-tunable warning thresholds and the derived Korean guidance line.
 * Single home of this math — used by the approval-context panel
 * and the org dashboard summary, so both always judge by the same
 * numbers. Capacity is platform-wide: physical capacity cannot be attributed
 * per org on shared nodes.
 */
@Service
public class OrgHeadroomService {

    static final String GUIDANCE_AMPLE = "자원에 여유가 있어 승인이 가능합니다.";
    static final String GUIDANCE_MEMORY = "메모리 여유가 부족해 신중한 승인이 필요합니다.";
    static final String GUIDANCE_VCPU = "vCPU 오버커밋 비율이 높아 신중한 승인이 필요합니다.";
    static final String GUIDANCE_BOTH = "vCPU와 메모리 여유가 모두 부족해 신중한 승인이 필요합니다.";

    private static final double DEFAULT_VCPU_OVERCOMMIT_WARN = 3.0;
    private static final double DEFAULT_MEMORY_USAGE_WARN = 0.8;

    private final VmRepository vmRepository;
    private final NodeRepository nodeRepository;
    private final SettingsService settingsService;

    public OrgHeadroomService(VmRepository vmRepository, NodeRepository nodeRepository,
            SettingsService settingsService) {
        this.vmRepository = vmRepository;
        this.nodeRepository = nodeRepository;
        this.settingsService = settingsService;
    }

    /**
     * Allocated-vs-capacity aggregates + threshold warnings + guidance for one
     * org — or platform-wide (all orgs) when {@code orgId} is null (SYS_ADMIN
     * dashboard without a drill-in).
     */
    public HeadroomResult headroom(Long orgId) {
        List<Vm> orgVms = vmRepository.findActiveByOrgId(orgId, VmStatus.DELETED);
        ResourceTotalsResponse allocated = ResourceTotalsResponse.of(orgVms);
        PlatformCapacity capacity = capacity();
        long cpuThreads = capacity.cpuThreads();
        long memoryMb = capacity.memoryMb();

        double vcpuRatio = cpuThreads == 0 ? 0.0 : round2((double) allocated.vcpu() / cpuThreads);
        double memoryRatio = memoryMb == 0 ? 0.0 : round2((double) allocated.memoryMb() / memoryMb);
        Double diskRatio = capacity.diskGb() == null || capacity.diskGb() == 0 ? null
                : round2((double) allocated.diskGb() / capacity.diskGb());
        double vcpuWarn = settingsService.decimal(SettingsService.VCPU_OVERCOMMIT_WARN,
                DEFAULT_VCPU_OVERCOMMIT_WARN);
        double memoryWarn = settingsService.decimal(SettingsService.MEMORY_USAGE_WARN,
                DEFAULT_MEMORY_USAGE_WARN);

        List<String> warnings = new ArrayList<>();
        if (vcpuRatio >= vcpuWarn) {
            warnings.add("vCPU 오버커밋 비율이 경고 임계값을 초과했습니다 (%.2f ≥ %.2f)."
                    .formatted(vcpuRatio, vcpuWarn));
        }
        if (memoryRatio >= memoryWarn) {
            warnings.add("메모리 할당 비율이 경고 임계값을 초과했습니다 (%.2f ≥ %.2f)."
                    .formatted(memoryRatio, memoryWarn));
        }
        return new HeadroomResult(allocated, cpuThreads, memoryMb, capacity.diskGb(), vcpuRatio,
                memoryRatio, diskRatio, List.copyOf(warnings),
                guidance(vcpuRatio >= vcpuWarn, memoryRatio >= memoryWarn));
    }

    /**
     * What the platform physically has right now: ACTIVE nodes only, so a node
     * in maintenance stops counting the moment it stops taking guests.
     *
     * <p>Disk is the odd one out. It is measured per node rather than declared
     * with the node, so one unmeasured ACTIVE node makes the sum a number
     * smaller than the truth — which would read as less headroom than there is.
     * The whole figure goes null in that case instead.
     *
     * <p>Which is what an added node looks like: a new node arrives with no
     * measurement, so the disk capacity of the whole platform reads null until
     * that node is measured, and every surface hanging off it (the dashboard
     * disk bar, the capacity trend's disk line) empties for as long. That is
     * this rule working, not a regression introduced by adding the node.
     */
    public PlatformCapacity capacity() {
        List<Node> nodes = nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE);
        boolean everyNodeMeasured = !nodes.isEmpty()
                && nodes.stream().allMatch(node -> node.getDiskCapacityGb() != null);
        return new PlatformCapacity(
                nodes.stream().mapToLong(Node::getCpuThreads).sum(),
                nodes.stream().mapToLong(Node::getMemoryMb).sum(),
                everyNodeMeasured ? nodes.stream().mapToLong(Node::getDiskCapacityGb).sum() : null);
    }

    private static String guidance(boolean vcpu, boolean memory) {
        if (vcpu && memory) {
            return GUIDANCE_BOTH;
        }
        if (memory) {
            return GUIDANCE_MEMORY;
        }
        if (vcpu) {
            return GUIDANCE_VCPU;
        }
        return GUIDANCE_AMPLE;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * One org's headroom snapshot (capacity = platform ACTIVE nodes). Disk
     * capacity and its ratio are null while any ACTIVE node is unmeasured, and
     * are advisory even when present: the thin pool is over-provisioned, so no
     * warning threshold hangs off them.
     */
    public record HeadroomResult(
            ResourceTotalsResponse allocated,
            long capacityVcpu,
            long capacityMemoryMb,
            @Nullable Long capacityDiskGb,
            double vcpuRatio,
            double memoryRatio,
            @Nullable Double diskRatio,
            List<String> warnings,
            String guidance) {
    }

    /** Physical capacity of the ACTIVE nodes, as of now. */
    public record PlatformCapacity(long cpuThreads, long memoryMb, @Nullable Long diskGb) {
    }
}
