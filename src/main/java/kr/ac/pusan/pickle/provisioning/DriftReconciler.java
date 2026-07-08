package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recurring 10-minute DB↔Proxmox drift detection (docs/plan/03
 * "reconciliation"): DB is intent, Proxmox is reality, humans resolve. Three
 * drift classes, <b>none of which ever destroys anything</b>:
 *
 * <ol>
 *   <li>DB VM missing in Proxmox → CAS to NEEDS_ADMIN ({@link #DETAIL_MISSING}).</li>
 *   <li>pickle-tagged Proxmox qemu VM unknown to the DB → WARN log only
 *       (surfacing beyond logs is the M5 drift report; never touched).</li>
 *   <li>Spec mismatch (maxcpu/maxmem vs granted vcpu/memory_mb) →
 *       informational {@code status_detail} flag, no state transition.</li>
 * </ol>
 *
 * <p>VMs with a live provisioning task are skipped (the pipeline is mid-flight
 * and transient inconsistency is expected), as are VMs already parked in
 * NEEDS_ADMIN for class ①. Errors are logged and swallowed per node so a
 * broken node cannot stop the recurring job.</p>
 */
@Component
public class DriftReconciler {

    public static final String JOB_ID = "drift-reconciler";

    static final String DETAIL_MISSING = "Proxmox에 VM 없음(드리프트)";
    static final String SPEC_DRIFT_PREFIX = "사양 불일치(드리프트)";

    /** Tag marking a Proxmox guest as pickle-managed (set by the provisioning pipeline). */
    static final String MANAGED_TAG = "pickle";

    private static final Logger log = LoggerFactory.getLogger(DriftReconciler.class);

    private static final long MIB = 1024L * 1024L;

    private final NodeRepository nodeRepository;
    private final VmRepository vmRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final ProxmoxClient proxmoxClient;

    public DriftReconciler(NodeRepository nodeRepository, VmRepository vmRepository,
            ProvisioningTaskRepository taskRepository, ProxmoxClient proxmoxClient) {
        this.nodeRepository = nodeRepository;
        this.vmRepository = vmRepository;
        this.taskRepository = taskRepository;
        this.proxmoxClient = proxmoxClient;
    }

    /**
     * One reconcile cycle. Public and argument-free so JobRunr's
     * {@code RecurringJobPostProcessor} (spring-boot-4 starter 8.7.1) can
     * register it; tests call it directly.
     */
    @Recurring(id = JOB_ID, interval = "PT10M")
    @Job(name = JOB_ID, retries = 0)
    public void reconcile() {
        try {
            List<Vm> withVmid = vmRepository.findByProxmoxVmidIsNotNull();
            Set<Long> liveTaskVmIds = taskRepository.findVmIdsWithStatusIn(ProvisioningTaskStatus.live());
            // Drift ①/③ working set: live rows only. DELETED/ERROR are terminal
            // states an operator already knows about (never auto-touched).
            Map<Long, List<Vm>> activeByNode = withVmid.stream()
                    .filter(vm -> vm.getStatus() != VmStatus.DELETED && vm.getStatus() != VmStatus.ERROR)
                    .collect(Collectors.groupingBy(Vm::getNodeId));
            // Drift ② baseline: any non-DELETED row claims its vmid. A DELETED
            // row whose guest still exists is genuine "unmanaged" drift.
            Set<Integer> knownVmids = withVmid.stream()
                    .filter(vm -> vm.getStatus() != VmStatus.DELETED)
                    .map(Vm::getProxmoxVmid)
                    .collect(Collectors.toSet());
            for (Node node : nodeRepository.findAll()) {
                if (node.getStatus() == NodeStatus.OFFLINE) {
                    continue;
                }
                try {
                    reconcileNode(node, activeByNode.getOrDefault(node.getId(), List.of()),
                            knownVmids, liveTaskVmIds);
                } catch (RuntimeException e) {
                    // Next cycle retries in 10 min; other nodes still reconcile.
                    log.warn("drift reconcile failed for node {}: {}", node.getName(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("drift reconcile cycle failed: {}", e.toString());
        }
    }

    private void reconcileNode(Node node, List<Vm> nodeVms, Set<Integer> knownVmids,
            Set<Long> liveTaskVmIds) {
        // Cluster-wide listing: existence is matched by vmid alone, so a VM
        // migrated to a sibling cluster node is not falsely flagged missing.
        Map<Integer, ClusterResource> qemuByVmid = new HashMap<>();
        for (ClusterResource resource : proxmoxClient.clusterResources(node.getApiHost(), "vm")) {
            if (resource.vmid() != null && "qemu".equals(resource.type())) {
                qemuByVmid.put(resource.vmid(), resource);
            }
        }

        for (Vm vm : nodeVms) {
            if (liveTaskVmIds.contains(vm.getId())) {
                continue; // pipeline mid-flight — transient drift is expected
            }
            ClusterResource resource = qemuByVmid.get(vm.getProxmoxVmid());
            if (resource == null) {
                flagMissing(vm);
            } else {
                reconcileSpec(vm, resource);
            }
        }

        // Drift ②: pickle-tagged guests nobody in the DB claims. Log only —
        // the monthly drift report is M5; auto-destroying is forbidden.
        for (ClusterResource resource : qemuByVmid.values()) {
            if (!knownVmids.contains(resource.vmid()) && hasManagedTag(resource.tags())) {
                log.warn("unmanaged pickle-tagged VM on Proxmox: vmid {} name '{}' node {} status {}"
                                + " — not in DB, leaving untouched (docs/plan/03)",
                        resource.vmid(), resource.name(), resource.node(), resource.status());
            }
        }
    }

    /** Drift ①: parks the VM for an operator (skips VMs already parked). */
    private void flagMissing(Vm vm) {
        if (vm.getStatus() == VmStatus.NEEDS_ADMIN) {
            return;
        }
        if (vmRepository.transitionStatus(vm.getId(), vm.getStatus(), VmStatus.NEEDS_ADMIN,
                DETAIL_MISSING, Instant.now()) == 1) {
            log.warn("vm {} (vmid {}) missing in Proxmox — flagged NEEDS_ADMIN",
                    vm.getId(), vm.getProxmoxVmid());
        }
    }

    /**
     * Drift ③: informational {@code status_detail} flag on spec mismatch, no
     * state transition. Cleared again once the specs agree (only if the current
     * detail is a spec-drift note — never wipes pipeline error messages).
     */
    private void reconcileSpec(Vm vm, ClusterResource resource) {
        if (resource.maxcpu() == null || resource.maxmem() == null) {
            return;
        }
        boolean mismatch = resource.maxcpu() != vm.getVcpu()
                || resource.maxmem() != vm.getMemoryMb() * MIB;
        String current = vm.getStatusDetail();
        if (mismatch) {
            String detail = "%s: Proxmox %dvCPU/%dMB ≠ DB %dvCPU/%dMB".formatted(SPEC_DRIFT_PREFIX,
                    resource.maxcpu(), resource.maxmem() / MIB, vm.getVcpu(), vm.getMemoryMb());
            if (!detail.equals(current)
                    && vmRepository.updateStatusDetail(vm.getId(), vm.getStatus(), detail,
                            Instant.now()) == 1) {
                log.info("vm {} (vmid {}) spec drift: {}", vm.getId(), vm.getProxmoxVmid(), detail);
            }
        } else if (current != null && current.startsWith(SPEC_DRIFT_PREFIX)) {
            vmRepository.updateStatusDetail(vm.getId(), vm.getStatus(), null, Instant.now());
        }
    }

    /** True when the semicolon/comma-separated PVE tag list contains {@code pickle}. */
    private static boolean hasManagedTag(String tags) {
        if (tags == null || tags.isBlank()) {
            return false;
        }
        for (String tag : tags.split("[;,]")) {
            if (MANAGED_TAG.equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }
}
