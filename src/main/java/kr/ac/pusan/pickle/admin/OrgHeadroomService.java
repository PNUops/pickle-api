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

    static final String GUIDANCE_AMPLE = "여유가 충분합니다. 요청 사양 그대로 승인해도 무리가 없습니다.";
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
    public OrgHeadroom headroom(Long orgId) {
        List<Vm> orgVms = vmRepository.findActiveByOrgId(orgId, VmStatus.DELETED);
        ResourceTotalsResponse allocated = ResourceTotalsResponse.of(orgVms);
        List<Node> nodes = nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE);
        long cpuThreads = nodes.stream().mapToLong(Node::getCpuThreads).sum();
        long memoryMb = nodes.stream().mapToLong(Node::getMemoryMb).sum();

        double vcpuRatio = cpuThreads == 0 ? 0.0 : round2((double) allocated.vcpu() / cpuThreads);
        double memoryRatio = memoryMb == 0 ? 0.0 : round2((double) allocated.memoryMb() / memoryMb);
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
        return new OrgHeadroom(allocated, cpuThreads, memoryMb, vcpuRatio, memoryRatio,
                List.copyOf(warnings), guidance(vcpuRatio >= vcpuWarn, memoryRatio >= memoryWarn));
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

    /** One org's headroom snapshot (capacity = platform ACTIVE nodes). */
    public record OrgHeadroom(
            ResourceTotalsResponse allocated,
            long capacityVcpu,
            long capacityMemoryMb,
            double vcpuRatio,
            double memoryRatio,
            List<String> warnings,
            String guidance) {
    }
}
